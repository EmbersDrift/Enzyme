package com.embers.packmod;

import com.embers.packmod.mixin.PackSelectionRefresh;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.zip.*;

/** Isolated launch only. Staged by tools/game/run_audit.py, excluded from release JARs. */
final class PackStandaloneAudit {
    private static final String LANGUAGE=System.getenv().getOrDefault("PACKMOD_AUDIT_LANGUAGE","zh_cn");
    private static int stage,age,ticks;
    private static Path packs;
    private static Screen home;
    private static PackSelectionScreen parent;
    private static byte[] original;
    private static byte[] overlayOriginal;
    private static Path realSource,realOutput;
    private static String realSignature;
    private static byte[] realBytes;
    private static java.util.concurrent.CompletableFuture<?> realReload;
    private static java.util.concurrent.CompletableFuture<PyramidConverter.Result> realConversion;
    private static final String REAL_PACK="00-real-customer-pack.zip";
    private static final String OVERLAY = "e-overlay.zip";
    private static final String[] NAMES={"a-applied.zip","b-pending.zip","c-convert.zip","d-untouched.zip"};
    static void init(){ClientTickEvents.END_CLIENT_TICK.register(PackStandaloneAudit::tick);}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void next(int value){stage=value;age=0;PackModClient.LOGGER.info("STANDALONE AUDIT stage {}",stage);}
    private static List<AbstractWidget> widgets(Screen s){return s.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();}
    private static AbstractWidget find(Screen s,String name){return widgets(s).stream().filter(w->w.getMessage().getString().equals(name)||VanillaUi.ui(w.getMessage()).getString().equals(name)).findFirst().orElseThrow();}
    private static void click(Screen s,AbstractWidget w){
        clickAt(s,w.getX()+w.getWidth()/2.0,w.getY()+w.getHeight()/2.0);
    }
    private static void clickAt(Screen s,double mx,double my){
        var e=new MouseButtonEvent(mx,my,new MouseButtonInfo(0,0));
        check(s.mouseClicked(e,false)||Minecraft.getInstance().gui.screen()!=s,"mouse action handled");s.mouseReleased(e);
    }
    private static int rowTop(net.minecraft.client.gui.screens.packs.TransferableSelectionList list,int index)throws Exception{
        return list.getRowTop(index);
    }
    private static void clickAvailable(Screen s,String name)throws Exception{
        for(var child:s.children())if(child instanceof net.minecraft.client.gui.screens.packs.TransferableSelectionList list){
            for(int i=0;i<list.children().size();i++){
                Object row=list.children().get(i);
                for(var field:row.getClass().getDeclaredFields())if(field.getType()==net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry.class){
                    field.setAccessible(true);
                    var e=(net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry)field.get(row);
                    if(e.getId().equals("file/"+name)&&e.canSelect()){
                        clickAt(s,list.getRowLeft()+20,rowTop(list,i)+12);return;
                    }
                }
            }
        }
        throw new AssertionError("Available native pack row not found: "+name);
    }
    private static boolean nativeSelected(String name){
        return ((PackSelectionRefresh)parent).packmod$model().getSelected().anyMatch(e->e.getId().equals("file/"+name));
    }
    private static void click(Screen s,String label){click(s,find(s,label));}
    private static AbstractWidget convert(Screen s){return widgets(s).stream().filter(w->w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("packmod.pack_conversion.convert_selected")).findFirst().orElseThrow();}
    private static boolean phase(Screen s,String name)throws Exception{
        if(!(s instanceof PackConversionScreen))return false;
        var f=PackConversionScreen.class.getDeclaredField("phase");f.setAccessible(true);return f.get(s).toString().equals(name);
    }
    private static PackSelectionScreen packScreen(Minecraft c){return packScreen(c,packs);}
    private static PackSelectionScreen packScreen(Minecraft c,Path directory){return new PackSelectionScreen(c.getResourcePackRepository(),repo->c.gui.setScreen(home),directory,Component.translatable("options.resourcepack"));}
    private static void shot(Minecraft c,String name){Screenshot.grab(c.gameDirectory,"standalone-"+name+".png",c.gameRenderer.mainRenderTarget(),1,x->{});}
    private static void tick(Minecraft c){
        if(++ticks>6000){PackModClient.LOGGER.error("STANDALONE AUDIT timeout stage {}",stage);c.stop();return;}
        if(stage==0&&ticks%100==0)PackModClient.LOGGER.info("STANDALONE AUDIT waiting for title: screen={}, overlay={}",c.gui.screen()==null?null:c.gui.screen().getClass().getName(),c.gui.overlay()==null?null:c.gui.overlay().getClass().getName());
        if(c.gui.overlay()!=null)return;age++;Screen screen=c.gui.screen();
        try{switch(stage){
            case 0->{if(!(screen instanceof TitleScreen)||age<25)return;
                Files.writeString(c.gameDirectory.toPath().resolve("conversion-target-check.json"),ConversionTarget.verify(PackConversionManager.target()).toString());
                c.getWindow().setWindowed(1280,800);c.options.guiScale().set(2);c.resizeGui();
                home=screen;packs=c.getResourcePackDirectory();Files.createDirectories(packs);
                try(var z=new ZipOutputStream(Files.newOutputStream(packs.resolve(NAMES[0])))){
                    z.putNextEntry(new ZipEntry("pack.mcmeta"));z.write("{\"pack\":{\"pack_format\":34,\"description\":\"Audit\"}}".getBytes());z.closeEntry();}
                original=Files.readAllBytes(packs.resolve(NAMES[0]));for(int i=1;i<NAMES.length;i++)Files.write(packs.resolve(NAMES[i]),original);
                c.options.languageCode=LANGUAGE;c.getLanguageManager().setSelected(LANGUAGE);
                c.getResourcePackRepository().reload();c.getResourcePackRepository().setSelected(List.of("vanilla","file/"+NAMES[0]));
                c.options.resourcePacks.clear();c.options.resourcePacks.add("file/"+NAMES[0]);c.reloadResourcePacks();next(1);}
            case 1->{if(age<30)return;
                check(c.getResourceManager().listPacks().anyMatch(p->p.packId().equals("file/"+NAMES[0])),"pack actually applied");
                parent=packScreen(c);c.gui.setScreen(parent);
                ((PackSelectionRefresh)parent).packmod$model().getUnselected().filter(e->e.getId().equals("file/"+NAMES[1])).findFirst().orElseThrow().select();next(2);}
            case 2->{if(!phase(screen,"SELECTING")||age<20)return;
                check(!find(screen,NAMES[0]).active&&!find(screen,NAMES[1]).active,"applied and pending packs protected");
                check(!convert(screen).active,"no implicit checkbox selection");
                click(screen,NAMES[2]);check(convert(screen).active,"eligible checkbox enables conversion");shot(c,"checklist");click(screen,VanillaUi.ui("Conversion Settings").getString());next(15);}
            case 3->{if(!phase(screen,"FINISHED")||age<20)return;
                check(!Files.exists(packs.resolve(NAMES[2])),"original archived out of root");
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("Old Pack").resolve(NAMES[2]))),"original bytes preserved");
                Path output=packs.resolve("[Java 26.2]"+NAMES[2]);check(PackInspector.inspect(output).supports(PackConversionManager.target()),"converted output compatible");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/"+output.getFileName()),"output not auto enabled");
                for(int i:new int[]{0,1,3})check(Files.exists(packs.resolve(NAMES[i])),"unchecked/protected source untouched");
                check(((PackSelectionRefresh)parent).packmod$model().getSelected().anyMatch(e->e.getId().equals("file/"+NAMES[1])),"pending selection survives refresh");
                shot(c,"result");click(screen,VanillaUi.ui("Done").getString());next(4);}
            case 4->{if(age<35)return;check(screen==parent,"no automatic repeat");
                click(screen,VanillaUi.ui("Check Pack Versions").getString());next(5);}
            case 5->{if(!phase(screen,"SELECTING")||age<20)return;
                check(find(screen,NAMES[3]).active,"manual recheck finds unchecked input");
                ((PackSelectionRefresh)parent).packmod$model().getSelected().filter(e->e.canUnselect()).toList().forEach(e->e.unselect());
                c.getResourcePackRepository().setSelected(List.of("vanilla"));c.options.resourcePacks.clear();c.reloadResourcePacks();c.gui.setScreen(home);next(6);}
            case 6->{if(age<30)return;parent=packScreen(c);c.gui.setScreen(parent);next(7);}
            case 7->{if(age<35)return;check(screen==parent,"reopening is manual only");
                click(screen,VanillaUi.ui("Check Pack Versions").getString());next(8);}
            case 8->{if(!phase(screen,"SELECTING")||age<20)return;
                for(int i:new int[]{0,1,3})check(find(screen,NAMES[i]).active,"disabled applied-away pack becomes eligible");
                click(screen,VanillaUi.ui("Select All").getString());click(screen,convert(screen));next(9);}
            case 9->{if(!phase(screen,"FINISHED")||age<20)return;
                for(String name:NAMES)check(Files.exists(packs.resolve("Old Pack").resolve(name)),"all original inputs archived");
                check(c.getResourcePackRepository().getSelectedIds().stream().noneMatch(id->id.startsWith("file/")),"outputs remain inactive");
                click(screen,VanillaUi.ui("Done").getString());next(10);}
            case 10->{if(age<30)return;click(screen,VanillaUi.ui("Check Pack Versions").getString());next(11);}
            case 11->{if(!phase(screen,"SELECTING")||age<20)return;
                check(widgets(screen).size()==2,"all-compatible screen keeps the independent settings button");shot(c,"all-compatible");
                check(Files.readString(c.gameDirectory.toPath().resolve("config/packmod-pack-conversions.json")).contains("\"automaticCheckDone\":true"),"first visit persisted");
                click(screen,VanillaUi.ui("Done").getString());next(35);}
            case 35->{if(screen!=parent||age<20)return;
                check(find(screen,VanillaUi.ui("Conversion Settings").getString()) instanceof net.minecraft.client.gui.components.Button,"resource pack menu exposes independent native settings button");
                shot(c,"pack-menu");click(screen,VanillaUi.ui("Conversion Settings").getString());next(36);}
            case 36->{if(!(screen instanceof ConversionOptionsScreen)||age<20)return;
                click(screen,VanillaUi.ui("Restore Defaults").getString());
                next(38);}
            case 38->{if(!(screen instanceof ConversionOptionsScreen)||age<20)return;
                var field=ConversionOptionsScreen.class.getDeclaredField("options");field.setAccessible(true);
                var restored=(ConversionOptions)field.get(screen);
                check(restored.equals(ConversionOptions.defaults())&&restored.generateMissing()&&restored.adaptShaders()&&restored.fixAlphaLayers(),"Restore Defaults enables fill and fixes");
                shot(c,"conversion-settings-defaults");click(screen,VanillaUi.ui("Save").getString());next(37);}
            case 37->{if(screen!=parent||age<20)return;
                check(ConversionOptions.read(c.gameDirectory.toPath().resolve("config/packmod-conversion-options.json")).equals(ConversionOptions.defaults()),"settings persist independently of conversion");next(12);}
            case 12->{if(age<30)return;
                try(var z=new ZipOutputStream(Files.newOutputStream(packs.resolve(OVERLAY)))){
                    z.putNextEntry(new ZipEntry("pack.mcmeta"));
                    z.write("{\"pack\":{\"pack_format\":34,\"description\":\"Overlay audit\"},\"overlays\":{\"entries\":[{\"formats\":[34,34],\"directory\":\"overlay\"}]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));z.closeEntry();
                    z.putNextEntry(new ZipEntry("overlay/assets/test/marker.txt"));z.write("preserved overlay".getBytes());z.closeEntry();}
                overlayOriginal=Files.readAllBytes(packs.resolve(OVERLAY));
                parent=packScreen(c);c.gui.setScreen(parent);click(parent,VanillaUi.ui("Check Pack Versions").getString());next(13);}
            case 13->{if(!phase(screen,"SELECTING")||age<20)return;
                check(find(screen,OVERLAY).active,"overlay conversion can be explicitly selected");
                var row=find(screen,OVERLAY);
                var field=row.getClass().getDeclaredField("row");field.setAccessible(true);
                Object data=field.get(row);var warning=data.getClass().getDeclaredMethod("warning");warning.setAccessible(true);
                check((boolean)warning.invoke(data),"overlay warning shown before conversion");
                shot(c,"overlay-checklist");click(screen,OVERLAY);click(screen,convert(screen));next(14);}
            case 14->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Arrays.equals(overlayOriginal,Files.readAllBytes(packs.resolve(OVERLAY))),"warning result leaves original in place");
                check(!Files.exists(packs.resolve("Old Pack").resolve(OVERLAY)),"warning result is not automatically archived");
                Path output=packs.resolve("[Java 26.2]"+OVERLAY);check(Files.exists(output),"warning output available for review");
                check(c.getResourcePackRepository().getAvailableIds().contains("file/"+output.getFileName()),"warning output is accepted into vanilla pack list");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/"+output.getFileName()),"warning output stays inactive");
                var row=find(screen,OVERLAY);var field=row.getClass().getDeclaredField("row");field.setAccessible(true);
                Object data=field.get(row);var warning=data.getClass().getDeclaredMethod("warning");warning.setAccessible(true);
                check((boolean)warning.invoke(data),"result distinguished as needs review");
                check(find(screen,VanillaUi.ui("Open Conversion Reports").getString()).active,"reports are accessible from result screen");
                try(var zip=new ZipFile(output.toFile())){check(zip.getEntry("overlay/assets/test/marker.txt")==null&&zip.getEntry("assets/test/marker.txt")!=null,"source overlay materialized into output base");}
                shot(c,"overlay-result");click(screen,VanillaUi.ui("Conversion History").getString());next(21);}
            case 15->{if(!(screen instanceof ConversionOptionsScreen)||age<15)return;
                check(PackConversionManager.options().generateMissing() && PackConversionManager.options().adaptShaders() && PackConversionManager.options().fixAlphaLayers(),"fresh settings enable resource generation and fixes");
                check(widgets(screen).stream().allMatch(net.minecraft.client.gui.components.Button.class::isInstance),"settings use native Minecraft buttons");
                click(screen,VanillaUi.ui("Complete Preset").getString());
                var optionField=ConversionOptionsScreen.class.getDeclaredField("options");optionField.setAccessible(true);
                check(((ConversionOptions)optionField.get(screen)).allowDestructive(),"complete preset enables existing core options");
                click(screen,VanillaUi.ui("Conservative Preset").getString());
                click(screen,VanillaUi.ui("Generate Missing Resources [OFF]").getString());
                click(screen,VanillaUi.ui("Generate Missing Resources [ON]").getString());
                shot(c,"conversion-options");click(screen,VanillaUi.ui("Save").getString());next(16);}
            case 16->{if(!phase(screen,"SELECTING")||age<15)return;check(!PackConversionManager.options().generateMissing(),"options toggle saved");click(screen,VanillaUi.ui("Preview Conversion").getString());next(17);}
            case 17->{if(!(screen instanceof ConversionPlanScreen)||age<20)return;
                var field=ConversionPlanScreen.class.getDeclaredField("lines");field.setAccessible(true);var lines=(List<?>)field.get(screen);
                if(lines.isEmpty())return;check(lines.stream().anyMatch(v->v.toString().contains("gui_surgeon")),"preview contains real native scheduler task");
                shot(c,"conversion-preview");click(screen,VanillaUi.ui("Done").getString());next(18);}
            case 18->{if(!phase(screen,"SELECTING")||age<15)return;click(screen,convert(screen));click(screen,VanillaUi.ui("Continue in Background").getString());next(27);}
            case 27->{if(screen!=parent||age<20)return;click(screen,VanillaUi.ui("Conversion Results").getString());next(3);}
            case 21->{if(!(screen instanceof PackHistoryScreen)||age<20)return;
                check(find(screen,VanillaUi.ui("Archive Reviewed Original").getString()).active,"reviewed original can be explicitly archived");
                var search=widgets(screen).stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow();search.setValue("e-overlay");
                check(find(screen,VanillaUi.ui("Archive Reviewed Original").getString()).active,"history search keeps matching entry");
                click(screen,Component.translatable("packmod.pack_conversion.filter.all").getString());
                check(!find(screen,VanillaUi.ui("Archive Reviewed Original").getString()).active,"completed filter excludes review output");
                click(screen,Component.translatable("packmod.pack_conversion.filter.completed").getString());
                check(find(screen,VanillaUi.ui("Archive Reviewed Original").getString()).active,"review filter retains warnings after restart-style history loading");
                shot(c,"conversion-history");click(screen,VanillaUi.ui("Report Details").getString());next(30);}
            case 30->{if(!(screen instanceof PackReportScreen)||age<20)return;
                var field=PackReportScreen.class.getDeclaredField("paragraphs");field.setAccessible(true);var lines=(List<?>)field.get(screen);
                if(lines.size()<2)return;check(lines.stream().anyMatch(v->v.toString().contains("overlays_flattened")),"detail page retains original warning code");
                shot(c,"report-details");click(screen,VanillaUi.ui("Export Diagnostics").getString());next(31);}
            case 31->{if(age<20)return;Path diagnostics=PackConversionManager.reportDirectory().getParent().resolve("diagnostics");
                if(!Files.isDirectory(diagnostics))return;
                try(var paths=Files.list(diagnostics)){var archive=paths.filter(p->p.toString().endsWith(".zip")).findFirst();if(archive.isEmpty())return;try(var zip=new ZipFile(archive.get().toFile())){check(zip.getEntry("environment.json")!=null,"UI exports diagnostic package");}}
                click(screen,VanillaUi.ui("Done").getString());next(32);}
            case 32->{if(!(screen instanceof PackHistoryScreen)||age<20)return;click(screen,VanillaUi.ui("Archive Reviewed Original").getString());next(22);}
            case 22->{if(PackConversionManager.busy()||age<20)return;
                check(!Files.exists(packs.resolve(OVERLAY))&&Files.exists(packs.resolve("Old Pack").resolve(OVERLAY)),"explicit archive action moved reviewed original");
                check(find(screen,VanillaUi.ui("Restore Original").getString()).active,"archive can be restored");click(screen,VanillaUi.ui("Restore Original").getString());next(23);}
            case 23->{if(PackConversionManager.busy()||age<20)return;
                check(Arrays.equals(overlayOriginal,Files.readAllBytes(packs.resolve(OVERLAY))),"UI restore preserved original bytes");
                check(Files.exists(packs.resolve("[Java 26.2]"+OVERLAY)),"restoring original leaves converted output intact");
                shot(c,"history-restored");click(screen,VanillaUi.ui("Done").getString());next(24);}
            case 24->{if(!phase(screen,"FINISHED")||age<15)return;click(screen,VanillaUi.ui("Done").getString());next(25);}
            case 25->{if(screen!=parent||age<20)return;
                Files.write(packs.resolve("f-cancel.zip"),original);click(screen,VanillaUi.ui("Check Pack Versions").getString());next(26);}
            case 26->{if(!phase(screen,"SELECTING")||age<20)return;
                click(screen,"f-cancel.zip");click(screen,convert(screen));check(phase(screen,"CONVERTING"),"conversion starts asynchronously");
                click(screen,VanillaUi.ui("Cancel Conversion").getString());next(28);}
            case 28->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("f-cancel.zip"))),"UI cancellation preserves original");
                check(!Files.exists(packs.resolve("[Java 26.2]f-cancel.zip")),"UI cancellation installs no output");
                shot(c,"conversion-cancelled");
                Path retryJob=Files.createTempDirectory(PackConversionManager.reportDirectory(),"retry-audit-");
                PackJobHistory.create(retryJob,packs.resolve("f-cancel.zip"),PackConversionFiles.signature(packs.resolve("f-cancel.zip")),ConversionOptions.defaults());
                PackJobHistory.update(retryJob,"failed","error","Isolated retry fixture");
                click(screen,VanillaUi.ui("Conversion History").getString());next(33);}
            case 33->{if(!(screen instanceof PackHistoryScreen)||age<20)return;
                if(!find(screen,VanillaUi.ui("Retry Failed Task").getString()).active)return;
                click(screen,VanillaUi.ui("Retry Failed Task").getString());next(34);}
            case 34->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Files.exists(packs.resolve("[Java 26.2]f-cancel.zip")),"retry publishes converted output");
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("Old Pack/f-cancel.zip"))),"retry archives original safely");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/[Java 26.2]f-cancel.zip"),"retry output stays inactive");
                shot(c,"retry-result");
                Files.write(packs.resolve("00-prompt.zip"),original);Files.write(packs.resolve("01-prompt-pending.zip"),original);
                c.getResourcePackRepository().reload();parent=packScreen(c);c.gui.setScreen(parent);next(40);}
            case 40->{if(screen!=parent||age<20)return;
                clickAvailable(screen,"01-prompt-pending.zip");next(41);}
            case 41->{if(age<20)return;
                check(screen instanceof PackConversionPrompt,"native incompatible arrow opens augmented confirmation");
                check(widgets(screen).stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).count()==3,"native Yes/No plus conversion choice");
                click(screen,Component.translatable("gui.yes").getString());next(42);}
            case 42->{if(screen!=parent||age<20)return;
                check(nativeSelected("01-prompt-pending.zip"),"native Yes still selects the incompatible pack");
                clickAvailable(screen,"00-prompt.zip");next(43);}
            case 43->{if(age<20)return;check(screen instanceof PackConversionPrompt,"second incompatible pack has its own prompt");
                shot(c,"incompatible-prompt");click(screen,Component.translatable("gui.no").getString());next(44);}
            case 44->{if(screen!=parent||age<20)return;
                check(!nativeSelected("00-prompt.zip")&&nativeSelected("01-prompt-pending.zip"),"native No preserves existing pending selections");
                clickAvailable(screen,"00-prompt.zip");next(45);}
            case 45->{if(!(screen instanceof PackConversionPrompt)||age<20)return;
                click(screen,VanillaUi.ui("Convert Resource Pack").getString());next(46);}
            case 46->{if(!phase(screen,"SELECTING")||age<20)return;
                var field=PackConversionScreen.class.getDeclaredField("selected");field.setAccessible(true);
                check(field.get(screen).equals(java.util.Set.of(packs.resolve("00-prompt.zip"))),"only the clicked pack is selected for conversion");
                check(convert(screen).active&&!nativeSelected("00-prompt.zip")&&nativeSelected("01-prompt-pending.zip"),"conversion target stays unused and pending selection survives");
                check(!find(screen,"01-prompt-pending.zip").active,"other pending pack remains protected");
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("00-prompt.zip"))),"navigation alone does not convert or alter input");
                shot(c,"prompt-conversion-selection");click(screen,VanillaUi.ui("Cancel").getString());next(47);}
            case 47->{if(screen!=parent||age<20)return;clickAvailable(screen,"00-prompt.zip");next(48);}
            case 48->{if(!(screen instanceof PackConversionPrompt)||age<20)return;
                check(screen.keyPressed(new net.minecraft.client.input.KeyEvent(256,0,0)),"native Escape is consumed");next(49);}
            case 49->{if(screen!=parent||age<20)return;
                check(!nativeSelected("00-prompt.zip"),"Escape leaves incompatible pack unselected");
                clickAvailable(screen,"00-prompt.zip");next(50);}
            case 50->{if(!(screen instanceof PackConversionPrompt)||age<20)return;click(screen,VanillaUi.ui("Convert Resource Pack").getString());next(51);}
            case 51->{if(!phase(screen,"SELECTING")||age<20)return;check(convert(screen).active,"prompt can be reopened after cancelling");click(screen,convert(screen));next(52);}
            case 52->{if(!phase(screen,"FINISHED")||age<20)return;
                check(Arrays.equals(original,Files.readAllBytes(packs.resolve("Old Pack/00-prompt.zip"))),"prompt conversion archives original bytes");
                Path promptOutput=packs.resolve("[Java 26.2]"+"00-prompt.zip");
                check(PackInspector.inspect(promptOutput).supports(PackConversionManager.target()),"prompt output has the actual target format");
                check(nativeSelected("01-prompt-pending.zip")&&Files.exists(packs.resolve("01-prompt-pending.zip")),"unrelated pending pack survives conversion and refresh");
                check(!c.getResourcePackRepository().getSelectedIds().contains("file/"+promptOutput.getFileName()),"prompt output is not automatically enabled");
                check(!(PackConversionPrompt.create(value->{},Component.empty(),Component.empty(),"vanilla") instanceof PackConversionPrompt),"built-in packs do not gain conversion prompts");
                check(PackConversionManager.promptSource("file/../outside.zip")==null,"invalid source ids cannot target another directory");
                shot(c,"prompt-conversion-result");
                Path dataDirectory=c.gameDirectory.toPath().resolve("world-datapacks");Files.createDirectories(dataDirectory);
                c.gui.setScreen(packScreen(c,dataDirectory));
                check(!(PackConversionPrompt.create(value->{},Component.empty(),Component.empty(),"file/01-prompt-pending.zip") instanceof PackConversionPrompt),"same-name data packs in another directory keep the native confirmation");
                c.gui.setScreen(parent);
                if(System.getenv("PACKMOD_AUDIT_REAL_PACK")!=null)next(60);else finish(c);}
            case 60->{if(age<20)return;
                realSource=Path.of(System.getenv("PACKMOD_AUDIT_REAL_PACK"));realSignature=PackConversionFiles.signature(realSource);
                realBytes=Files.readAllBytes(realSource);Files.copy(realSource,packs.resolve(REAL_PACK));PackConversionManager.options(ConversionOptions.defaults());
                c.getResourcePackRepository().setSelected(List.of("vanilla"));c.options.resourcePacks.clear();
                c.getResourcePackRepository().reload();parent=packScreen(c);c.gui.setScreen(parent);next(59);}
            case 59->{if(screen!=parent||age<20)return;
                if(PackInspector.inspect(packs.resolve(REAL_PACK)).supports(PackConversionManager.target())){
                    realConversion=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                        try{return PyramidConverter.convertWithReport(PackInspector.inspect(packs.resolve(REAL_PACK)),PackConversionManager.target(),packs,c.gameDirectory.toPath().resolve("packmod-tools/pyramid"),ignored->{});}
                        catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
                    });next(58);
                }else{clickAvailable(parent,REAL_PACK);next(61);}}
            case 58->{if(!realConversion.isDone())return;realOutput=realConversion.join().output();next(64);}
            case 61->{if(!(screen instanceof PackConversionPrompt)||age<20)return;click(screen,VanillaUi.ui("Convert Resource Pack").getString());next(62);}
            case 62->{if(!phase(screen,"SELECTING")||age<20)return;
                check(find(screen,REAL_PACK).active,"real resource pack can be selected");click(screen,convert(screen));next(63);}
            case 63->{if(!phase(screen,"FINISHED")||age<20)return;
                try(var paths=Files.list(packs)){realOutput=paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(REAL_PACK)&&p.getFileName().toString().endsWith(REAL_PACK)).findFirst().orElseThrow();}
                check(PackInspector.inspect(realOutput).supports(PackConversionManager.target()),"real conversion target matches running game");
                Path retained=Files.exists(packs.resolve(REAL_PACK))?packs.resolve(REAL_PACK):packs.resolve("Old Pack").resolve(REAL_PACK);
                check(Arrays.equals(Files.readAllBytes(retained),realBytes),"real source copy preserved after conversion");next(64);}
            case 64->{if(age<20)return;
                c.getResourcePackRepository().reload();String id="file/"+realOutput.getFileName();
                check(c.getResourcePackRepository().getPack(id).getCompatibility().isCompatible(),"vanilla accepts real pack format");
                c.getResourcePackRepository().setSelected(List.of("vanilla",id));c.options.resourcePacks.clear();c.options.resourcePacks.add(id);
                c.gui.setScreen(home);realReload=c.reloadResourcePacks();next(65);}
            case 65->{if(age<40)return;
                if(age>100&&c.gui.overlay()==null&&!c.getResourceManager().listPacks().anyMatch(p->p.packId().equals("file/"+realOutput.getFileName())))throw new AssertionError("Real pack reload fell back to vanilla");
                if(!realReload.isDone())return;realReload.join();
                check(c.getResourceManager().listPacks().anyMatch(p->p.packId().equals("file/"+realOutput.getFileName())),"real converted pack remains applied after resource reload");
                check(PackConversionFiles.signature(realSource).equals(realSignature),"provided real pack remains unchanged");
                var data=new com.google.gson.JsonObject();data.addProperty("passed",true);data.addProperty("target",PackConversionManager.target().toString());data.addProperty("source_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(realBytes)));data.addProperty("output",realOutput.getFileName().toString());data.addProperty("resource_reload_passed",true);
                Files.writeString(c.gameDirectory.toPath().resolve("real-pack-pass.json"),data.toString());
                parent=packScreen(c);c.gui.setScreen(parent);next(66);}
            case 66->{if(age<20)return;shot(c,"real-pack-loaded");next(67);}
            case 67->{if(age<20||!Files.isRegularFile(c.gameDirectory.toPath().resolve("screenshots/standalone-real-pack-loaded.png")))return;finish(c);}
        }}catch(Throwable error){PackModClient.LOGGER.error("STANDALONE AUDIT failed stage "+stage,error);c.stop();next(99);}
    }
    private static void finish(Minecraft c)throws Exception {
        Files.writeString(c.gameDirectory.toPath().resolve("standalone-pass.txt"),"PASS: standalone launch, localized mouse input ("+LANGUAGE+"), first/manual checks, applied/pending protection, selective conversion, archive/refresh, overlay warnings, report details, presets, task preview, background results, history search/filter, diagnostic export, archive/restore, cancellation, failed-task retry, native incompatible prompt Yes/No/Escape/conversion navigation, clicked-pack-only selection, pending selection preservation, prompt conversion, data pack exclusion and no auto enable.\n");
        PackModClient.LOGGER.info("STANDALONE AUDIT PASS");c.stop();next(99);
    }
}
