"""Run the compiled intermediary MOD in a real Fabric production client.

The separate probe contains only the mouse audit; it is never included in delivery.
Libraries come from Mojang/Fabric manifests and are checked against published hashes.
"""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,re,shutil,subprocess,sys,time,urllib.request,urllib.parse,zipfile
from generate import ROOT,VERSIONS,MOD_VERSION,generate
from build_all import verify
from toolchains import java_home,installation_paths
sys.path.insert(0,str(ROOT/'tools/game'))
import real_pack
import game_assets

CACHE=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))
LIBRARIES=ROOT/'build/compat-production-libraries'

def loader_profile(mc):
    directory=LIBRARIES/'profiles';directory.mkdir(parents=True,exist_ok=True)
    path=directory/('fabric-'+mc+'-0.19.5.json')
    def validate(payload):
        value=json.loads(payload)
        assert value['id']=='fabric-loader-0.19.5-'+mc and value['mainClass']=='net.fabricmc.loader.impl.launch.knot.KnotClient'
        assert any(lib['name']=='net.fabricmc:fabric-loader:0.19.5' for lib in value['libraries'])
        return value
    if path.is_file():return validate(path.read_bytes())
    for attempt in range(3):
        try:
            payload=urllib.request.urlopen('https://meta.fabricmc.net/v2/versions/loader/'+mc+'/0.19.5/profile/json',timeout=30).read()
            profile=validate(payload);pending=path.with_suffix('.json.tmp');pending.write_bytes(payload);pending.replace(path);return profile
        except (OSError,ValueError):
            if attempt==2:raise

def coordinate(name):
    group,artifact,version,*classifier=name.split(':')
    filename=artifact+'-'+version+('-'+classifier[0] if classifier else '')+'.jar'
    return group,artifact,version,filename,group.replace('.','/')+'/'+artifact+'/'+version+'/'+filename

def library(item):
    group,artifact,version,filename,path=coordinate(item['name'])
    entry=item.get('download',{})
    dest=LIBRARIES/path;dest.parent.mkdir(parents=True,exist_ok=True)
    digest=entry.get('sha1') or item.get('sha1')
    def valid(p):return p.exists() and (not digest or hashlib.sha1(p.read_bytes()).hexdigest()==digest)
    if not valid(dest):
        candidates=list((CACHE/'caches/modules-2/files-2.1'/group/artifact/version).glob('*/'+filename))
        found=next((p for p in candidates if valid(p)),None)
        if found:shutil.copyfile(found,dest)
        else:urllib.request.urlretrieve(entry.get('url') or item.get('url','https://libraries.minecraft.net/')+path,dest)
    if not valid(dest):raise ValueError('Library hash mismatch: '+item['name'])
    return dest

def allowed(item):
    rules=item.get('rules');value=rules is None
    for rule in rules or []:
        system=rule.get('os',{})
        if system.get('name','windows')!='windows':continue
        if system.get('arch','amd64') not in {'amd64','x86_64'}:continue
        if rule.get('features'):continue
        value=rule['action']=='allow'
    return value

def fabric_api(version):
    api=library(dict(name='net.fabricmc.fabric-api:fabric-api:'+version,url='https://maven.fabricmc.net/'))
    with zipfile.ZipFile(api) as z:
        if json.loads(z.read('fabric.mod.json')).get('jars'):return api
    # Old Maven API artifacts are dependency aggregators, not installable bundles.
    # Use Fabric's official full release asset, exactly what a player installs.
    filename='fabric-api-'+version+'.jar'
    dest=LIBRARIES/'fabric-releases'/filename;dest.parent.mkdir(parents=True,exist_ok=True)
    def valid_bundle(path):
        try:
            with zipfile.ZipFile(path) as z:
                meta=json.loads(z.read('fabric.mod.json'))
                return meta['version']==version and bool(meta.get('jars')) and z.testzip() is None
        except (OSError,zipfile.BadZipFile,KeyError,ValueError):return False
    if not valid_bundle(dest):
        # Direct official asset URLs work without the unauthenticated API limit.
        url='https://github.com/FabricMC/fabric-api/releases/download/'+urllib.parse.quote(version,safe='')+'/'+urllib.parse.quote(filename,safe='')
        pending=dest.with_suffix('.jar.tmp');urllib.request.urlretrieve(url,pending)
        assert valid_bundle(pending),'Fabric API release download incomplete or wrong version'
        pending.replace(dest)
    return dest

def audit(mc):
    root=mc=='26.2'
    spec=dict(java=25,format=88,fabric_api='0.161.0+26.2') if root else VERSIONS[mc]
    jar=ROOT/('build/libs' if root else 'build/compat-delivery')/('enzyme-'+mc+'-'+MOD_VERSION+'.jar')
    if not root:verify(mc,jar)
    else:
        with zipfile.ZipFile(jar) as archive:
            assert json.loads(archive.read('fabric.mod.json'))['version']==MOD_VERSION
            assert not any('PackStandaloneAudit' in n or 'ProductionProbe' in n for n in archive.namelist())
    java=(Path(os.environ['JAVA_HOME']) if root else java_home(spec['java']))/'bin/java.exe'
    paths=installation_paths()
    if root:
        directory=ROOT/'build/root-production-probe';package=directory/'java/com/embers/packmod';package.mkdir(parents=True,exist_ok=True)
        (package/'ProductionProbe.java').write_text('package com.embers.packmod; public final class ProductionProbe implements net.fabricmc.api.ClientModInitializer { public void onInitializeClient(){PackStandaloneAudit.init();}}')
        resources=directory/'resources';resources.mkdir(exist_ok=True)
        (resources/'fabric.mod.json').write_text(json.dumps(dict(schemaVersion=1,id='packmod_probe',version='1.0.0',environment='client',entrypoints=dict(client=['com.embers.packmod.ProductionProbe']),depends=dict(packmod='>='+MOD_VERSION))))
        subprocess.run([str(ROOT/'gradlew.bat'),'-I',str(ROOT/'tools/game/production-probe.init.gradle'),'rootProbeJar','--console=plain','--offline'],cwd=ROOT,check=True)
    else:
        try:
            generate(mc,audit=True,probe=True)
            subprocess.run([str(ROOT/'gradlew.bat'),'-p','compat','-Pmc='+mc,'-Porg.gradle.java.installations.paths='+paths,'remapProbe','--console=plain'],cwd=ROOT,check=True)
        finally:generate(mc)
    profile=loader_profile(mc)
    info=json.loads((CACHE/'caches/fabric-loom'/mc/'mojang_minecraft_info.json').read_text())
    assets=game_assets.ensure(CACHE/'caches/fabric-loom',info)
    items=list(profile['libraries'])
    for lib in info['libraries']:
        if not allowed(lib):continue
        if 'artifact' in lib.get('downloads',{}):items.append(dict(name=lib['name'],download=lib['downloads']['artifact']))
        classifier=lib.get('natives',{}).get('windows')
        if classifier:
            classifier=classifier.replace('${arch}','64')
            entry=lib.get('downloads',{}).get('classifiers',{}).get(classifier)
            if entry:items.append(dict(name=lib['name']+':'+classifier,download=entry))
    # Fabric's profile overrides Minecraft's ASM versions, as launchers do.
    # Putting both versions on Knot's classpath fails before the MOD is loaded.
    unique={}
    for item in items:
        parts=item['name'].split(':')
        key=(parts[0],parts[1],tuple(parts[3:]))
        unique.setdefault(key,item)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:libs=list(pool.map(library,unique.values()))
    run=ROOT/'build/compat-production-runs'/mc/('run-'+str(time.time_ns()));(run/'mods').mkdir(parents=True)
    natives=run/'natives';natives.mkdir()
    for lib in libs:
        if 'natives-windows' in lib.name:
            with zipfile.ZipFile(lib) as z:
                for name in z.namelist():
                    if name.endswith('.dll'):(natives/Path(name).name).write_bytes(z.read(name))
    shutil.copyfile(jar,run/'mods'/jar.name)
    probe=ROOT/('build/root-production-probe' if root else 'build/compat/'+mc+'/libs')/('packmod-probe-'+mc+'.jar');shutil.copyfile(probe,run/'mods'/probe.name)
    api=fabric_api(spec['fabric_api'])
    shutil.copyfile(api,run/'mods'/api.name)
    (run/'options.txt').write_text('onboardAccessibility:false\n')
    classpath=os.pathsep.join(str(p) for p in libs+[CACHE/'caches/fabric-loom'/mc/'minecraft-client.jar'])
    command=[str(java),'-Xmx2G','-Dfabric.headless=true','-Djava.library.path='+str(natives),'-Dminecraft.launcher.brand=PackModAudit','-Dminecraft.launcher.version=1','-Dfabric.development=false',*profile.get('arguments',{}).get('jvm',[]),'-cp',classpath,profile['mainClass'],
        '--username','PackModAudit','--version',mc,'--gameDir',str(run),'--assetsDir',str(CACHE/'caches/fabric-loom/assets'),'--assetIndex',info['assetIndex']['id'],
        '--accessToken','0','--uuid','00000000000000000000000000000000','--userType','legacy','--versionType','release']
    print('Production JAR audit '+mc,flush=True)
    log=run/'launcher.log'
    with log.open('w',encoding='utf-8') as out:
        process=subprocess.Popen(command,cwd=run,stdout=out,stderr=subprocess.STDOUT)
        try:result=process.wait(timeout=420 if os.environ.get('PACKMOD_AUDIT_REAL_PACK') else 240)
        except subprocess.TimeoutExpired:
            process.terminate();process.wait(timeout=10);raise RuntimeError('Production audit timed out: '+str(log))
    if result or not (run/'standalone-pass.txt').exists():
        print(log.read_text(encoding='utf-8',errors='replace')[-12000:]);raise RuntimeError('Production JAR audit failed: '+str(log))
    clarity=run/'ui-clarity-check';clarity.mkdir()
    subprocess.run([str(java.with_name('javac.exe')),'-d',str(clarity),str(ROOT/'tools/game/UiClarityCheck.java')],check=True)
    screenshots=['checklist','all-compatible','conversion-options','conversion-preview','conversion-history','report-details']
    subprocess.run([str(java),'-cp',str(clarity),'UiClarityCheck',
        *(str(run/'screenshots'/('standalone-'+name+'.png')) for name in screenshots)],check=True)
    target=json.loads((run/'conversion-target-check.json').read_text())
    assert target['passed'] and target['running_minecraft']==mc and target['major']==spec['format'] and target['minor']==0 and target['loader']=='fabric'
    record=dict(minecraft=mc,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),java=spec['java'],production_audit_directory=str(run),production_jar_audit_passed=True,ui_clarity_passed=True,conversion_target_check=target)
    record['official_assets']=assets
    sample=real_pack.verify(run)
    if sample:record['real_pack_check']=sample
    (run.parent/'verification.json').write_text(json.dumps(record,indent=2)+'\n')
    print('PRODUCTION JAR AUDIT PASS '+mc,flush=True)
    return record

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('versions',nargs='+',choices=[*VERSIONS,'26.2']);parser.add_argument('--real-pack');args=parser.parse_args();real_pack.configure(args.real_pack)
    for mc in args.versions:audit(mc)
