"""Generate version-specific Minecraft adapters from the maintained MOD layer.

Conversion source, templates and dependencies are strictly hash checked, never edited.
Generated files live under build; compatibility changes remain reviewable here.
"""
from pathlib import Path
import argparse, hashlib, json, re, shutil

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = json.loads((ROOT/'compat/versions.json').read_text())
MOD_VERSION = next(line.split('=', 1)[1].strip() for line in (ROOT/'gradle.properties').read_text().splitlines() if line.startswith('version='))

def generate(mc, audit=False, probe=False, output_dir=None):
    if (ROOT/'src/client/java/com/embers/packmod/PackStandaloneAudit.java').exists():
        raise RuntimeError('The root game audit has temporarily staged shared sources; wait for it to restore them before generating adapters.')
    config = VERSIONS[mc]
    baseline = json.loads((ROOT/'tools/upstream/core-baseline.json').read_text())
    frozen = {p.relative_to(ROOT).as_posix() for directory in ('src', 'tests', 'UImage') for p in (ROOT/'pyramid-bridge'/directory).rglob('*') if p.is_file()}
    frozen.update('pyramid-bridge/'+name for name in ('Cargo.toml', 'Cargo.lock', 'LICENSE-2-Pyramid'))
    if frozen != set(baseline['files']):
        raise ValueError('Frozen core file set changed; do not refresh the baseline to hide changes.')
    for path, digest in baseline['files'].items():
        if hashlib.sha256((ROOT/path).read_bytes()).hexdigest() != digest:
            raise ValueError('Frozen core changed: '+path)
    output = Path(output_dir) if output_dir is not None else ROOT/'build/compat-generated'/mc
    java = output/'java/com/embers/packmod'
    java.mkdir(parents=True, exist_ok=True)
    for old in ('AeroButton.java','AeroUi.java'):
        (java/old).unlink(missing_ok=True)
    events = config['input_events']; pose = config['pose_stack']
    graphics_type = 'PoseStack' if pose else 'GuiGraphics'
    graphics_import = 'com.mojang.blaze3d.vertex.PoseStack' if pose else 'net.minecraft.client.gui.GuiGraphics'
    for source in (ROOT/'src/client/java/com/embers/packmod').glob('*.java'):
        if source.stem in {'RoundedTextures', 'RoundedMaskLayout', 'UiFade'}: continue
        text = source.read_text(encoding='utf-8')
        if mc != '1.21.11':
            text = text.replace('extends Button.Plain', 'extends Button')
        text = text.replace('import net.minecraft.client.gui.GuiGraphicsExtractor;', 'import '+graphics_import+';')
        text = text.replace('import net.minecraft.resources.Identifier;', '')
        text = text.replace('GuiGraphicsExtractor', 'CompatGraphics').replace('Math.clamp(', 'CompatMath.clamp(')
        text = text.replace('Util.getPlatform().openPath(', 'CompatPlatform.open(')
        text = text.replace('Screens.getWidgets(screen).add(', '((com.embers.packmod.mixin.ScreenWidgets)screen).packmod$add(')
        text = text.replace('.gui.setScreen(', '.setScreen(').replace('.gui.screen()', '.screen').replace('.gui.overlay()', '.getOverlay()')
        text = text.replace('.gui.toastManager()', '.getToastManager()' if mc.startswith('1.21') else '.getToasts()')
        text = text.replace('SharedConstants.getCurrentVersion().name()', 'SharedConstants.getCurrentVersion().getName()')
        if mc!='1.21.11':text=text.replace('import net.minecraft.util.Util;', 'import net.minecraft.Util;')
        if mc in {'1.18.1','1.20.1'}:text=text.replace('SystemToastId.PERIODIC_NOTIFICATION', 'SystemToastIds.PACK_COPY_FAILURE')
        if mc.startswith('1.21'):
            text = text.replace('SharedConstants.getCurrentVersion().getName()', 'SharedConstants.getCurrentVersion().name()')
        if source.stem == 'ConversionTarget' and mc in {'1.18.1','1.20.1','1.20.6','1.21.8'}:
            method = 'packVersion' if mc == '1.21.8' else 'getPackVersion'
            text = text.replace('packVersion(PackType.CLIENT_RESOURCES)', method+'(PackType.CLIENT_RESOURCES)')
            text = text.replace('new PackInspector.Version(format.major(), format.minor())', 'new PackInspector.Version(format, 0)')
            if mc == '1.18.1':
                text = text.replace('import net.minecraft.server.packs.PackType;', 'import com.mojang.bridge.game.PackType;')
                text = text.replace('PackType.CLIENT_RESOURCES', 'PackType.RESOURCE')
        text = text.replace('extractPanorama(g,delta)', 'compatPanorama(g,delta)').replace('extractPanorama(g, delta)', 'compatPanorama(g, delta)')
        text = text.replace('super.extractRenderState(g,', 'super.render(raw,')
        text = text.replace('super.extractBackground(g,', 'super.compatBackground(g,')
        # Keep graphics-independent screen bodies, adapt only Minecraft entry signatures.
        text = re.sub(r'public void extractBackground\(CompatGraphics g,\s*int mx,\s*int my,\s*float delta\)\s*\{',
                      'protected void compatBackground(CompatGraphics g,int mx,int my,float delta){', text)
        text = re.sub(r'public void extractRenderState\(CompatGraphics g,\s*int mx,\s*int my,\s*float delta\)\s*\{',
                      'public void render('+graphics_type+' raw,int mx,int my,float delta){CompatGraphics g=new CompatGraphics(raw);compatBackground(g,mx,my,delta);', text)
        text = text.replace('extends AbstractWidget', 'extends CompatWidget')
        text = text.replace('extractWidgetRenderState(', 'compatRender(')
        text = text.replace('import org.joml.Matrix3x2fStack;', '')
        text = text.replace('Matrix3x2fStack pose = graphics.pose();', 'var pose = graphics.pose();')
        text = text.replace('if(RoundedTextures.draw(graphics,x,y,width,height,radius,color,outline))return;', '')
        if source.stem == 'PackConversionPrompt' and mc in {'1.18.1','1.20.1','1.20.6'}:
            text = text.replace('import net.minecraft.client.gui.layouts.LinearLayout;', '')
            text = text.replace('addButtons(LinearLayout buttons)', 'addButtons(int y)')
            text = text.replace('super.addButtons(buttons);', 'super.addButtons(y);')
            text = text.replace('layout.addChild(conversionButton(0, 0));',
                'addExitButton(conversionButton((width-Math.min(200,width-40))/2, y+24));')
        if not events:
            text = text.replace('import net.minecraft.client.input.KeyEvent;', '').replace('import net.minecraft.client.input.MouseButtonEvent;', '')
            text = text.replace('keyPressed(KeyEvent event)', 'keyPressed(int key,int scanCode,int modifiers)').replace('event.key()', 'key')
            text = text.replace('super.keyPressed(event)', 'super.keyPressed(key,scanCode,modifiers)')
            text = text.replace('onClick(MouseButtonEvent event, boolean doubled)', 'onClick(double mx,double my)')
            text = text.replace('mouseClicked(MouseButtonEvent event, boolean doubled)', 'mouseClicked(double mx,double my,int button)')
            text = text.replace('super.mouseClicked(event, doubled)', 'super.mouseClicked(mx,my,button)')
            text = text.replace('mouseDragged(MouseButtonEvent event, double dx, double dy)', 'mouseDragged(double mx,double my,int button,double dx,double dy)')
            text = text.replace('super.mouseDragged(event, dx, dy)', 'super.mouseDragged(mx,my,button,dx,dy)')
        if mc in {'1.18.1','1.20.1'}:
            text = re.sub(r'mouseScrolled\(double (\w+),\s*double (\w+),\s*double dx,\s*double dy\)', r'mouseScrolled(double \1,double \2,double dy)', text)
            text = text.replace('super.mouseScrolled(x, y, dx, dy)', 'super.mouseScrolled(x,y,dy)')
        if pose:
            text=text.replace('.packmod$packDirectory()', '.packmod$packDirectoryFile().toPath()')
            if source.stem == 'NativeButton':
                text=text.replace(', DEFAULT_NARRATION);', ');')
                # 1.18's two texture halves only support buttons up to 200x20.
                # Keep the native renderer and center standard-size controls in
                # wider layout slots rather than sampling outside widgets.png.
                text=text.replace('super(x, y, width, height, VanillaUi.ui(label)',
                    'super(x + Math.max(0,width-200)/2, y, Math.min(width,200), Math.min(height,20), VanillaUi.ui(label)')
                text=text.replace('\n}', '''
    private CompatTooltip compatTooltip;
    public void setTooltip(CompatTooltip value) { compatTooltip=value; }
    @Override public void renderButton(PoseStack raw,int mx,int my,float delta) {
        super.renderButton(raw,mx,my,delta);
        if(isHovered&&compatTooltip!=null) compatTooltip.render(raw,mx,my);
    }
}''').replace('import net.minecraft.client.gui.components.Button;', 'import net.minecraft.client.gui.components.Button;\nimport com.mojang.blaze3d.vertex.PoseStack;')
            text=text.replace('List.of("fabricloader","fabric-api")', 'List.of("fabricloader","fabric")')
            text=text.replace('client.getResourcePackDirectory().toAbsolutePath()', 'client.getResourcePackDirectory().toPath().toAbsolutePath()')
            text=text.replace('map(PackResources::packId)', 'map(p -> "file/"+p.getName())')
            text=text.replace('entry.getId()', 'CompatEntry.id(entry)')
            text=text.replace('protected void updateWidgetNarration(', 'public void updateNarration(')
            text=text.replace('Component.empty()', 'CompatText.literal("")')
            text = text.replace('import net.minecraft.client.gui.components.Tooltip;', 'import com.embers.packmod.CompatTooltip;')
            text = text.replace('net.minecraft.client.gui.components.Tooltip.create', 'CompatTooltip.create').replace('Tooltip.create', 'CompatTooltip.create').replace('CompatCompatTooltip','CompatTooltip')
            text = text.replace('Component.literal(', 'CompatText.literal(').replace('Component.translatable(', 'CompatText.translatable(')
            text = text.replace('SystemToastId.PERIODIC_NOTIFICATION', 'SystemToastIds.PACK_COPY_FAILURE')
            # ScreenEvents accepts any AbstractWidget; use our button instead of newer builder API.
            text = re.sub(r'net.minecraft.client.gui.components.Button.builder\(\s*VanillaUi.ui\("([^"]+)"\),\s*button -> ([^\n]+?)\)\s*\.bounds\(([^;]+?)\)\.build\(\)',
                          lambda m: 'new NativeButton('+m[3]+',"'+m[1]+'",button -> '+m[2]+')',text)
            text = text.replace('input.setHint(CompatText.translatable("packmod.pack_conversion.search_history"));','')
        (java/source.name).write_text(text, encoding='utf-8')
    mixins = java/'mixin';mixins.mkdir(exist_ok=True)
    shutil.copyfile(ROOT/'src/client/java/com/embers/packmod/mixin/PackSelectionRefresh.java',mixins/'PackSelectionRefresh.java')
    if pose:
        refresh=mixins/'PackSelectionRefresh.java'
        refresh.write_text(refresh.read_text().replace('@Accessor("packDir") java.nio.file.Path packmod$packDirectory();',
            '@Accessor("packDir") java.io.File packmod$packDirectoryFile();'),encoding='utf-8')
    entry_mixin=(ROOT/'src/client/java/com/embers/packmod/mixin/PackEntryConversionMixin.java').read_text(encoding='utf-8')
    if pose:
        entry_mixin=entry_mixin.replace('method = "handlePackSelection"', 'method = "mouseClicked"')
        entry_mixin=entry_mixin.replace('pack.getId()', '((EntryPack)pack).packmod$pack().getId()')
    (mixins/'PackEntryConversionMixin.java').write_text(entry_mixin,encoding='utf-8')
    (mixins/'ScreenWidgets.java').write_text('''package com.embers.packmod.mixin;
import net.minecraft.client.gui.screens.Screen; import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.Renderable; import net.minecraft.client.gui.narration.NarratableEntry;
import org.spongepowered.asm.mixin.Mixin; import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Screen.class) public interface ScreenWidgets { @Invoker("addRenderableWidget") <T extends GuiEventListener & Renderable & NarratableEntry> T packmod$add(T widget); }
''')
    if pose:
        widgets=mixins/'ScreenWidgets.java';widgets.write_text(widgets.read_text().replace('net.minecraft.client.gui.components.Renderable','net.minecraft.client.gui.components.Widget').replace(' & Renderable &',' & Widget &'))
        (mixins/'EntryPack.java').write_text('''package com.embers.packmod.mixin; import org.spongepowered.asm.mixin.Mixin; import org.spongepowered.asm.mixin.gen.Accessor; import net.minecraft.server.packs.repository.Pack;
@Mixin(targets="net.minecraft.client.gui.screens.packs.PackSelectionModel$EntryBase") public interface EntryPack { @Accessor("pack") Pack packmod$pack(); }
''')
        (java/'CompatEntry.java').write_text('package com.embers.packmod; import net.minecraft.client.gui.screens.packs.PackSelectionModel; final class CompatEntry {static String id(PackSelectionModel.Entry e){return ((com.embers.packmod.mixin.EntryPack)e).packmod$pack().getId();}}')
    (java/'CompatMath.java').write_text('package com.embers.packmod; final class CompatMath { static int clamp(int v,int a,int b){return Math.max(a,Math.min(v,b));} static float clamp(float v,float a,float b){return Math.max(a,Math.min(v,b));}}')
    util='net.minecraft.util.Util' if mc=='1.21.11' else 'net.minecraft.Util'
    opening='openPath(p)' if mc=='1.21.11' else 'openFile(p.toFile())'
    (java/'CompatPlatform.java').write_text('package com.embers.packmod; final class CompatPlatform {static void open(java.nio.file.Path p){'+util+'.getPlatform().'+opening+';}}')
    (java/'CompatWidget.java').write_text(widget_adapter(graphics_type, graphics_import, pose),encoding='utf-8')
    (java/'CompatGraphics.java').write_text(graphics_adapter(graphics_type, graphics_import, mc),encoding='utf-8')
    if pose:
        (java/'CompatText.java').write_text('package com.embers.packmod; import net.minecraft.network.chat.*; final class CompatText {static Component literal(String s){return new TextComponent(s);} static Component translatable(String s,Object... a){return new TranslatableComponent(s,a);}}')
        (java/'CompatTooltip.java').write_text('''package com.embers.packmod; import net.minecraft.network.chat.Component;
record CompatTooltip(Component text) {
 static CompatTooltip create(Component c){return new CompatTooltip(c);}
 void render(com.mojang.blaze3d.vertex.PoseStack raw,int mx,int my){
  var client=net.minecraft.client.Minecraft.getInstance();
  if(client.screen!=null)client.screen.renderTooltip(raw,client.font.split(text,Math.min(260,client.getWindow().getGuiScaledWidth()-24)),mx,my);
 }
}''')
    if audit:
        text=(ROOT/'tools/game/PackStandaloneAudit.java').read_text(encoding='utf-8')
        text=text.replace('.gui.setScreen(','.setScreen(').replace('.gui.screen()','.screen').replace('.gui.overlay()','.getOverlay()')
        text=text.replace('c.gameRenderer.mainRenderTarget()','c.getMainRenderTarget()')
        text=text.replace('c.resizeGui()','c.resizeDisplay()')
        text=text.replace('\\"pack_format\\":34','\\"pack_format\\":1')
        text=text.replace('\\"formats\\":[34,34]','\\"formats\\":[1,1]')
        text=text.replace('v.toString().contains("gui_surgeon")','v.toString().contains("delete_font_folder")')
        text=text.replace('check(PackInspector.inspect(output).supports(PackConversionManager.target()),"converted output compatible");',
            'check(PackInspector.inspect(output).supports(PackConversionManager.target()),"converted output compatible");check(c.getResourcePackRepository().getPack("file/"+output.getFileName()).getCompatibility().isCompatible(),"vanilla Minecraft accepts converted target format");')
        text=text.replace('check(Files.exists(output),"warning output available for review");',
            'check(Files.exists(output),"warning output available for review");check(c.getResourcePackRepository().getPack("file/"+output.getFileName()).getCompatibility().isCompatible(),"vanilla Minecraft accepts reviewed output format");')
        text=re.sub(r'packs.resolve\("\[Java 26\.2\]"\+([^\)]+)\)',r'outputPath(\1)',text)
        text=text.replace('packs.resolve("[Java 26.2]f-cancel.zip")','outputPath("f-cancel.zip")')
        text=text.replace('"file/[Java 26.2]f-cancel.zip"','"file/"+outputPath("f-cancel.zip").getFileName()')
        if not mc.startswith('1.21'):text=text.replace('c.gameDirectory,"standalone-"+name+".png",c.getMainRenderTarget(),1,x->{}','c.gameDirectory,"standalone-"+name+".png",c.getMainRenderTarget(),x->{}')
        if not events:
            text=text.replace('import net.minecraft.client.input.MouseButtonEvent;','').replace('import net.minecraft.client.input.MouseButtonInfo;','')
            start=text.index('        var e=new MouseButtonEvent(');end=text.index('\n    }',start)
            text=text[:start]+'''        check(s.mouseClicked(mx,my,0)||net.minecraft.client.Minecraft.getInstance().screen!=s,"mouse action handled");s.mouseReleased(mx,my,0);'''+text[end:]
            text=text.replace('screen.keyPressed(new net.minecraft.client.input.KeyEvent(256,0,0))','screen.keyPressed(256,0,0)')
        if mc in {'1.18.1','1.20.1','1.20.6'}:
            text=text.replace('return list.getRowTop(index);', '''var mappings=net.fabricmc.loader.api.FabricLoader.getInstance().getMappingResolver();
        String method=mappings.mapMethodName("intermediary","net.minecraft.class_350","method_25337","(I)I");
        var row=net.minecraft.client.gui.components.AbstractSelectionList.class.getDeclaredMethod(method,int.class);
        row.setAccessible(true);return (int)row.invoke(list,index);''')
        if pose:
            text=text.replace('p.packId().equals("file/"+realOutput.getFileName())','p.getName().equals(realOutput.getFileName().toString())')
            text=text.replace('Component.empty()', 'CompatText.literal("")')
            text=text.replace('Component.translatable(', 'CompatText.translatable(').replace('c.options.guiScale().set(2)','c.options.guiScale=2')
            text=text.replace('packs=c.getResourcePackDirectory();','packs=c.getResourcePackDirectory().toPath();')
            text=text.replace('new PackSelectionScreen(c.getResourcePackRepository(),repo->c.setScreen(home),directory,','new PackSelectionScreen(home,c.getResourcePackRepository(),repo->c.setScreen(home),directory.toFile(),')
            text=text.replace('p.packId().equals("file/"+NAMES[0])','p.getName().equals(NAMES[0])')
            text=text.replace('e.getId()', 'CompatEntry.id(e)')
            text=text.replace('import net.minecraft.network.chat.contents.TranslatableContents;','import net.minecraft.network.chat.TranslatableComponent;')
            text=text.replace('w.getMessage().getContents() instanceof TranslatableContents t','w.getMessage() instanceof TranslatableComponent t')
            text=text.replace('c.getLanguageManager().setSelected(LANGUAGE)','c.getLanguageManager().setSelected(c.getLanguageManager().getLanguage(LANGUAGE))')
            text=text.replace('w.getX()','w.x').replace('w.getY()','w.y')
        helper='''    private static Path outputPath(String name)throws Exception {
        try(var files=Files.list(packs)){return files.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(name)&&p.getFileName().toString().endsWith(name)).findFirst().orElse(packs.resolve("__audit_missing_output__"+name));}
    }
'''
        text=text.replace('    static void init()',helper+'    static void init()')
        (java/'PackStandaloneAudit.java').write_text(text,encoding='utf-8')
        if not probe:
            client=java/'PackModClient.java';text=client.read_text().replace('        PackConversionManager.init();','        PackConversionManager.init();\n        PackStandaloneAudit.init();');client.write_text(text,encoding='utf-8')
    else:
        (java/'PackStandaloneAudit.java').unlink(missing_ok=True)
    if probe:
        (java/'ProductionProbe.java').write_text('package com.embers.packmod; public final class ProductionProbe implements net.fabricmc.api.ClientModInitializer { public void onInitializeClient(){PackStandaloneAudit.init();}}')
        directory=output/'probe-resources';directory.mkdir(exist_ok=True)
        (directory/'fabric.mod.json').write_text(json.dumps(dict(schemaVersion=1,id='packmod_probe',version='1.0.0',environment='client',entrypoints=dict(client=['com.embers.packmod.ProductionProbe']),depends=dict(packmod='>='+MOD_VERSION))))
    else:(java/'ProductionProbe.java').unlink(missing_ok=True)
    # Add neutral version-appropriate backdrop helpers; legacy versions do not have state-extraction hooks.
    fading=java/'FadingScreen.java';text=fading.read_text()
    # Each adapted screen draws its backdrop at the start of render. In 1.20.6,
    # Screen.render calls renderBackground again before drawing widgets: vanilla
    # blur then processes the panel/text already submitted by our screen. Own the
    # background hooks so only the explicit compatBackground pass draws anything.
    if pose:
        native_background='super.renderBackground(g.raw,0);'
        background_hooks='''@Override public void renderBackground(PoseStack raw) {}
@Override public void renderBackground(PoseStack raw,int color) {}
'''
        text=text.replace('import net.minecraft.client.gui.screens.Screen;', 'import net.minecraft.client.gui.screens.Screen;\nimport com.mojang.blaze3d.vertex.PoseStack;')
    elif mc=='1.20.1':
        native_background='super.renderBackground(g.raw);'
        background_hooks='@Override public void renderBackground(GuiGraphics raw) {}\n'
        text=text.replace('import net.minecraft.client.gui.screens.Screen;', 'import net.minecraft.client.gui.screens.Screen;\nimport net.minecraft.client.gui.GuiGraphics;')
    else:
        native_background='super.renderBackground(g.raw,x,y,delta);'
        background_hooks='@Override public void renderBackground(GuiGraphics raw,int mx,int my,float delta) {}\n'
        text=text.replace('import net.minecraft.client.gui.screens.Screen;', 'import net.minecraft.client.gui.screens.Screen;\nimport net.minecraft.client.gui.GuiGraphics;')
    helper=background_hooks+'protected void compatBackground(CompatGraphics g,int x,int y,float delta){'+native_background+'}\n'
    fading.write_text(text.replace('    protected FadingScreen', '    '+helper+'    protected FadingScreen'),encoding='utf-8')
    resources=output/'resources';resources.mkdir(exist_ok=True)
    shutil.copytree(ROOT/'src/client/resources',resources,dirs_exist_ok=True)
    (resources/'assets/packmod/conversion-target.json').write_text(json.dumps(dict(minecraft=mc,major=config['format'],minor=0,loader='fabric'),indent=2)+'\n')
    shutil.copyfile(ROOT/'LICENSE',resources/'LICENSE')
    shutil.copyfile(ROOT/'NOTICE.md',resources/'NOTICE.md')
    meta=json.loads((ROOT/'src/main/resources/fabric.mod.json').read_text())
    meta['version']=MOD_VERSION;meta['depends'].update(minecraft=mc,java='>='+str(config['java']),fabricloader='>=0.16.14')
    if pose:
        meta['depends'].pop('fabric-api');meta['depends']['fabric']='*'
    (resources/'fabric.mod.json').write_text(json.dumps(meta,indent=2)+'\n')
    (resources/'packmod.client.mixins.json').write_text(json.dumps(dict(required=True,package='com.embers.packmod.mixin',compatibilityLevel='JAVA_'+str(config['java']),client=['PackSelectionRefresh','ScreenWidgets','PackEntryConversionMixin']+(['EntryPack'] if pose else []),injectors=dict(defaultRequire=1)),indent=2)+'\n')
    print('Generated '+mc+' (Java '+str(config['java'])+'); '+str(len(frozen))+' frozen core files verified',flush=True)

def widget_adapter(kind, imp, pose):
    method='renderButton' if pose else 'renderWidget'
    extra='public int getX(){return x;} public int getY(){return y;} public int getHeight(){return height;} private CompatTooltip compatTooltip; public void setTooltip(CompatTooltip t){compatTooltip=t;}' if pose else ''
    tooltip='''if(isHovered&&compatTooltip!=null){var client=net.minecraft.client.Minecraft.getInstance();var lines=client.font.split(compatTooltip.text(),Math.min(260,client.getWindow().getGuiScaledWidth()-24));int tx=Math.min(mx+10,client.getWindow().getGuiScaledWidth()-280),ty=Math.min(my+12,client.getWindow().getGuiScaledHeight()-lines.size()*10-12);tx=Math.max(4,tx);ty=Math.max(4,ty);var g=new CompatGraphics(raw);g.fill(tx-3,ty-3,Math.min(client.getWindow().getGuiScaledWidth()-3,tx+263),ty+lines.size()*10+3,0xF0181828);for(int i=0;i<lines.size();i++)g.text(client.font,lines.get(i),tx,ty+i*10,0xFFFFFFFF,false);}''' if pose else ''
    return f'''package com.embers.packmod;
import {imp}; import net.minecraft.client.gui.components.AbstractWidget; import net.minecraft.network.chat.Component;
abstract class CompatWidget extends AbstractWidget {{
 CompatWidget(int x,int y,int w,int h,Component text){{super(x,y,w,h,text);}}
 {extra}
 @Override public void {method}({kind} raw,int mx,int my,float delta){{compatRender(new CompatGraphics(raw),mx,my,delta);{tooltip}}}
 protected abstract void compatRender(CompatGraphics g,int mx,int my,float delta);
}}
'''

def graphics_adapter(kind, imp, mc):
    pose=mc=='1.18.1';matrix=mc.startswith('1.21')
    drawing='''public void text(Font f,String s,int x,int y,int c,boolean shadow){if(shadow)f.drawShadow(raw,s,x,y,c);else f.draw(raw,s,x,y,c);}
public void text(Font f,Component s,int x,int y,int c,boolean shadow){if(shadow)f.drawShadow(raw,s,x,y,c);else f.draw(raw,s,x,y,c);}
public void text(Font f,FormattedCharSequence s,int x,int y,int c,boolean shadow){if(shadow)f.drawShadow(raw,s,x,y,c);else f.draw(raw,s,x,y,c);}
public void fill(int x,int y,int x2,int y2,int c){GuiComponent.fill(raw,x,y,x2,y2,c);}
public void fillGradient(int x,int y,int x2,int y2,int c,int d){fill(x,y,x2,y2,c);}''' if pose else '''public void text(Font f,String s,int x,int y,int c,boolean shadow){raw.drawString(f,s,x,y,c,shadow);}
public void text(Font f,Component s,int x,int y,int c,boolean shadow){raw.drawString(f,s,x,y,c,shadow);}
public void text(Font f,FormattedCharSequence s,int x,int y,int c,boolean shadow){raw.drawString(f,s,x,y,c,shadow);}
public void fill(int x,int y,int x2,int y2,int c){raw.fill(x,y,x2,y2,c);}
public void fillGradient(int x,int y,int x2,int y2,int c,int d){raw.fillGradient(x,y,x2,y2,c,d);}'''
    stack='raw' if pose else 'raw.pose()'
    push=stack+'.'+('pushMatrix()' if matrix else 'pushPose()')
    pop=stack+'.'+('popMatrix()' if matrix else 'popPose()')
    translate=stack+'.translate(x,y'+('' if matrix else ',0')+')'
    scale=stack+'.scale(x,y'+('' if matrix else ',1')+')'
    return f'''package com.embers.packmod;
import {imp}; import net.minecraft.client.gui.Font; import net.minecraft.client.gui.GuiComponent; import net.minecraft.network.chat.Component; import net.minecraft.util.FormattedCharSequence;
final class CompatGraphics {{
 final {kind} raw; CompatGraphics({kind} g){{raw=g;}}
 {drawing}
 public void centeredText(Font f,Component s,int x,int y,int c){{text(f,s,x-f.width(s)/2,y,c,true);}}
 public void blurBeforeThisStratum(){{}}
 public Pose pose(){{return new Pose();}}
 final class Pose {{void pushMatrix(){{{push};}} void popMatrix(){{{pop};}} void translate(float x,float y){{{translate};}} void scale(float x,float y){{{scale};}}}}
}}
'''.replace('import net.minecraft.client.gui.GuiComponent;', 'import net.minecraft.client.gui.GuiComponent;' if pose else '')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mc',choices=VERSIONS);parser.add_argument('--audit',action='store_true');parser.add_argument('--probe',action='store_true');args=parser.parse_args();generate(args.mc,args.audit or args.probe,args.probe)
