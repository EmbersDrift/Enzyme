"""Generate NeoForge adapters from the same maintained MOD and frozen core."""
from pathlib import Path
import argparse,json,shutil,sys,re,hashlib,importlib.util

ROOT=Path(__file__).resolve().parents[1]
module_spec=importlib.util.spec_from_file_location('enzyme_fabric_generator',ROOT/'compat/generate.py')
fabric=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(fabric)
fabric_generate,MOD_VERSION,FABRIC_VERSIONS=fabric.generate,fabric.MOD_VERSION,fabric.VERSIONS
VERSIONS=json.loads((ROOT/'neoforge/versions.json').read_text())

def generate(mc,audit=False):
    if (ROOT/'src/client/java/com/embers/packmod/PackStandaloneAudit.java').exists():
        raise RuntimeError('Wait for the root game audit to restore shared production sources before generating NeoForge adapters.')
    spec=VERSIONS[mc];out=ROOT/'build/neoforge-generated'/mc
    java=out/'java';resources=out/'resources'
    # Never retain classes or metadata from a previous generator/audit revision.
    for directory in (java,resources,out/'audit-java',out/'audit-resources'):
        if directory.exists():shutil.rmtree(directory)
    if mc!='26.2':
        assert spec['format']==FABRIC_VERSIONS[mc]['format']
        fabric_generate(mc,audit=audit,probe=audit,output_dir=out)
    else:
        baseline=json.loads((ROOT/'tools/upstream/core-baseline.json').read_text())
        for name,digest in baseline['files'].items():
            assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest,name
        package=java/'com/embers/packmod';package.mkdir(parents=True,exist_ok=True)
        for p in (ROOT/'src/client/java/com/embers/packmod').glob('*.java'):
            if p.stem not in {'RoundedTextures','RoundedMaskLayout','UiFade'}:shutil.copyfile(p,package/p.name)
        mixins=package/'mixin';mixins.mkdir(exist_ok=True)
        for name in ['PackSelectionRefresh.java','PackEntryConversionMixin.java']:
            shutil.copyfile(ROOT/'src/client/java/com/embers/packmod/mixin'/name,mixins/name)
        shutil.copytree(ROOT/'src/client/resources',resources,dirs_exist_ok=True)
        for name in ['LICENSE','NOTICE.md']:shutil.copyfile(ROOT/name,resources/name)
        if audit:shutil.copyfile(ROOT/'tools/game/PackStandaloneAudit.java',package/'PackStandaloneAudit.java')
    package=java/'com/embers/packmod'
    (package/'ProductionProbe.java').unlink(missing_ok=True)
    (resources/'fabric.mod.json').unlink(missing_ok=True)
    (package/'mixin/ScreenWidgets.java').unlink(missing_ok=True)
    for p in package.glob('*.java'):
        s=p.read_text(encoding='utf8')
        s=s.replace('net.fabricmc.fabric.api.client.event.lifecycle.v1.','com.embers.packmod.platform.')
        s=s.replace('net.fabricmc.fabric.api.client.screen.v1.','com.embers.packmod.platform.')
        s=s.replace('net.fabricmc.loader.api.FabricLoader','com.embers.packmod.platform.ModLoader').replace('FabricLoader','ModLoader')
        s=s.replace('import net.fabricmc.api.ClientModInitializer;','').replace(' implements ClientModInitializer','')
        s=s.replace('List.of("fabricloader","fabric-api")','List.of("neoforge")')
        s=s.replace('((com.embers.packmod.mixin.ScreenWidgets)screen).packmod$add(', 'Screens.getWidgets(screen).add(')
        if p.stem=='PackModClient':s=s.replace('    @Override public void onInitializeClient()', '    public void onInitializeClient()')
        if p.stem=='PackStandaloneAudit' and mc in {'1.20.1','1.20.6'}:
            start=s.index('        var mappings=')
            end=s.index('return (int)row.invoke(list,index);',start)+len('return (int)row.invoke(list,index);')
            method='m_7610_' if spec.get('legacy') else 'getRowTop'
            s=s[:start]+f'''        var row=net.minecraft.client.gui.components.AbstractSelectionList.class.getDeclaredMethod("{method}",int.class);
        row.setAccessible(true);return (int)row.invoke(list,index);'''+s[end:]
        p.write_text(s,encoding='utf8')
    platform=package/'platform';platform.mkdir(exist_ok=True)
    for p in (ROOT/'neoforge/platform').glob('*.java'):shutil.copyfile(p,platform/p.name)
    (package/'NeoEntrypoint.java').write_text('''package com.embers.packmod;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
@Mod(value="packmod",dist=Dist.CLIENT)
public final class NeoEntrypoint {
 public NeoEntrypoint(){com.embers.packmod.platform.NeoPlatform.install(()->new PackModClient().onInitializeClient());}
}
''',encoding='utf8')
    mixins=['PackSelectionRefresh','PackEntryConversionMixin']
    config=dict(required=True,package='com.embers.packmod.mixin',compatibilityLevel='JAVA_'+str(spec['java']),client=mixins,injectors=dict(defaultRequire=1))
    if spec.get('legacy'):config.update(refmap='packmod.refmap.json',minVersion='0.8')
    (resources/'packmod.client.mixins.json').write_text(json.dumps(config,indent=2)+'\n')
    (resources/'assets/packmod/conversion-target.json').write_text(json.dumps(dict(minecraft=mc,major=spec['format'],minor=0,loader='neoforge'),indent=2)+'\n')
    toml=f'''modLoader="javafml"
loaderVersion="[1,)"
license="MIT"
[[mods]]
modId="packmod"
version="{MOD_VERSION}"
displayName="Enzyme"
logoFile="assets/packmod/icon.png"
displayURL="https://github.com/EmbersDrift/Enzyme"
authors="EmbersDrift"
description="Inspect and convert resource packs for this Minecraft version."
[[mixins]]
config="packmod.client.mixins.json"
[[dependencies.packmod]]
modId="neoforge"
type="required"
versionRange="[{spec['neoforge']},)"
ordering="NONE"
side="CLIENT"
[[dependencies.packmod]]
modId="minecraft"
type="required"
versionRange="[{mc}]"
ordering="NONE"
side="CLIENT"
'''
    (resources/'META-INF').mkdir(exist_ok=True)
    (resources/'META-INF/neoforge.mods.toml').write_text(toml,encoding='utf8')
    if spec.get('legacy'):
        (resources/'pack.mcmeta').write_text(json.dumps(dict(pack=dict(pack_format=spec['format'],description='Enzyme MOD resources')))+'\n')
    auditjava=out/'audit-java/com/embers/packmod';auditjava.mkdir(parents=True,exist_ok=True)
    if audit:
        shutil.move(package/'PackStandaloneAudit.java',auditjava/'PackStandaloneAudit.java')
        probe=out/'audit-java/com/embers/packmodprobe';probe.mkdir(parents=True,exist_ok=True)
        (probe/'ProductionProbe.java').write_text('''package com.embers.packmodprobe;
import net.neoforged.fml.common.Mod;import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;import net.neoforged.neoforge.client.event.ClientTickEvent;
@Mod(value="packmod_probe",dist=Dist.CLIENT) public final class ProductionProbe {
 private boolean loaded;
 public ProductionProbe(){NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event)->{
  if(loaded)return;loaded=true;
  try {
   Class<?> anchor=Class.forName("com.embers.packmod.PackModClient");
   java.nio.file.Files.writeString(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("production-origin.txt"),anchor.getProtectionDomain().getCodeSource().getLocation().toString());
   var lookup=java.lang.invoke.MethodHandles.privateLookupIn(anchor,java.lang.invoke.MethodHandles.lookup());
   byte[] bytes;
   try(var stream=getClass().getResourceAsStream("/META-INF/enzyme-audit/PackStandaloneAudit.class.bin")){bytes=stream.readAllBytes();}
   Class<?> audit=lookup.defineClass(bytes);var init=audit.getDeclaredMethod("init");init.setAccessible(true);init.invoke(null);
  }catch(Throwable failure){failure.printStackTrace();throw new IllegalStateException("Cannot load isolated audit",failure);}
 });}
}
''')
        r=out/'audit-resources/META-INF';r.mkdir(parents=True,exist_ok=True)
        (r/'neoforge.mods.toml').write_text(f'''modLoader="javafml"
loaderVersion="[1,)"
license="MIT"
[[mods]]
modId="packmod_probe"
version="1.0.0"
displayName="Enzyme isolated audit"
[[dependencies.packmod_probe]]
modId="packmod"
type="required"
versionRange="[{MOD_VERSION}]"
ordering="AFTER"
side="CLIENT"
''')
        if spec.get('legacy'):
            (r.parent/'pack.mcmeta').write_text(json.dumps(dict(pack=dict(pack_format=spec['format'],description='Enzyme isolated audit resources')))+'\n')
    else:(package/'PackStandaloneAudit.java').unlink(missing_ok=True)
    if spec.get('legacy'):
        # NeoForge 47.x predates the package rename and uses SRG in production.
        for p in [*java.rglob('*.java'),*(out/'audit-java').rglob('*.java')]:
            s=p.read_text(encoding='utf8').replace('net.neoforged.fml','net.minecraftforge.fml').replace('net.neoforged.api','net.minecraftforge.api')
            s=s.replace('net.neoforged.neoforge.common.NeoForge','net.minecraftforge.common.MinecraftForge').replace('NeoForge.EVENT_BUS','MinecraftForge.EVENT_BUS')
            s=s.replace('net.neoforged.neoforge.client.event.ClientTickEvent','net.minecraftforge.event.TickEvent')
            s=s.replace('net.neoforged.neoforge.', 'net.minecraftforge.')
            s=s.replace('(ClientTickEvent.Post event) -> {','(TickEvent.ClientTickEvent event) -> {\n            if(event.phase != TickEvent.Phase.END)return;')
            s=s.replace('(ClientTickEvent.Post event)->{','(TickEvent.ClientTickEvent event)->{if(event.phase!=TickEvent.Phase.END)return;')
            s=s.replace('@Mod(value="packmod",dist=Dist.CLIENT)', '@Mod("packmod")')
            s=s.replace('@Mod(value="packmod_probe",dist=Dist.CLIENT)', '@Mod("packmod_probe")')
            s=s.replace('public NeoEntrypoint(){', 'public NeoEntrypoint(){if(net.minecraftforge.fml.loading.FMLEnvironment.dist!=Dist.CLIENT)return;')
            s=s.replace('public ProductionProbe(){','public ProductionProbe(){if(net.minecraftforge.fml.loading.FMLEnvironment.dist!=Dist.CLIENT)return;')
            s=s.replace('List.of("neoforge")','List.of("forge")')
            p.write_bytes(s.encode('utf8'))
        toml=toml.replace('loaderVersion="[1,)"','loaderVersion="[47,)"').replace('[[mixins]]\nconfig="packmod.client.mixins.json"\n','')
        toml=toml.replace('modId="neoforge"','modId="forge"').replace('type="required"','mandatory=true').replace(spec['neoforge'],spec['neoforge'].split('-',1)[1])
        (resources/'META-INF/neoforge.mods.toml').unlink()
        (resources/'META-INF/mods.toml').write_bytes(toml.encode('utf8'))
        if audit:
            original=out/'audit-resources/META-INF/neoforge.mods.toml'
            s=original.read_text().replace('loaderVersion="[1,)"','loaderVersion="[47,)"').replace('type="required"','mandatory=true')
            (original.parent/'mods.toml').write_bytes(s.encode('utf8'));original.unlink()
    print('Generated NeoForge '+mc+' target '+str(spec['format'])+'.0',flush=True)
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mc',choices=VERSIONS);p.add_argument('--audit',action='store_true');args=p.parse_args();generate(args.mc,args.audit)
