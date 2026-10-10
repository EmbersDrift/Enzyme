"""Run the compiled intermediary MOD in a real Fabric production client.

The separate probe contains only the mouse audit; it is never included in delivery.
Libraries come from Mojang/Fabric manifests and are checked against published hashes.
"""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,re,shutil,subprocess,time,urllib.request,urllib.parse,zipfile
from generate import ROOT,VERSIONS,generate
from build_all import verify
from toolchains import java_home,installation_paths

CACHE=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))
LIBRARIES=ROOT/'build/compat-production-libraries'

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
    release=json.loads(urllib.request.urlopen(urllib.request.Request(
        'https://api.github.com/repos/FabricMC/fabric-api/releases/tags/'+urllib.parse.quote(version,safe=''),
        headers={'User-Agent':'PackModCompatibilityAudit'})).read())
    asset=next(a for a in release['assets'] if a['name']=='fabric-api-'+version+'.jar')
    dest=LIBRARIES/'fabric-releases'/asset['name'];dest.parent.mkdir(parents=True,exist_ok=True)
    if not dest.exists() or dest.stat().st_size!=asset['size']:urllib.request.urlretrieve(asset['browser_download_url'],dest)
    assert dest.stat().st_size==asset['size'],'Fabric API release download incomplete'
    if asset.get('digest'):
        algorithm,digest=asset['digest'].split(':',1)
        assert hashlib.new(algorithm,dest.read_bytes()).hexdigest()==digest
    with zipfile.ZipFile(dest) as z:assert json.loads(z.read('fabric.mod.json')).get('jars'),'Fabric API bundle modules missing'
    return dest

def audit(mc):
    spec=VERSIONS[mc];jar=ROOT/'build/compat-delivery'/('enzyme-'+mc+'-0.4.0.jar');verify(mc,jar)
    java=java_home(spec['java'])/'bin/java.exe'
    paths=installation_paths()
    try:
        generate(mc,audit=True,probe=True)
        subprocess.run([str(ROOT/'gradlew.bat'),'-p','compat','-Pmc='+mc,'-Porg.gradle.java.installations.paths='+paths,'remapProbe','--console=plain'],cwd=ROOT,check=True)
    finally:generate(mc)
    profile=json.loads(urllib.request.urlopen('https://meta.fabricmc.net/v2/versions/loader/'+mc+'/0.19.5/profile/json').read())
    info=json.loads((CACHE/'caches/fabric-loom'/mc/'mojang_minecraft_info.json').read_text())
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
    probe=ROOT/'build/compat'/mc/'libs'/('packmod-probe-'+mc+'.jar');shutil.copyfile(probe,run/'mods'/probe.name)
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
        try:result=process.wait(timeout=240)
        except subprocess.TimeoutExpired:
            process.terminate();process.wait(timeout=10);raise RuntimeError('Production audit timed out: '+str(log))
    if result or not (run/'standalone-pass.txt').exists():
        print(log.read_text(encoding='utf-8',errors='replace')[-12000:]);raise RuntimeError('Production JAR audit failed: '+str(log))
    record=dict(minecraft=mc,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),java=spec['java'],production_audit_directory=str(run),production_jar_audit_passed=True)
    (run.parent/'verification.json').write_text(json.dumps(record,indent=2)+'\n')
    print('PRODUCTION JAR AUDIT PASS '+mc,flush=True)
    return record

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('versions',nargs='+',choices=VERSIONS);args=parser.parse_args()
    for mc in args.versions:audit(mc)
