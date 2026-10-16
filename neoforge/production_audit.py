"""Run release JARs under the official NeoForge production launcher."""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,shutil,subprocess,sys,time,zipfile
from generate import ROOT,VERSIONS,MOD_VERSION,generate
from launcher import CACHE,HOME,allowed,coordinate,install,java_home,library,proxy_args
sys.path.insert(0,str(ROOT/'tools/game'))
import real_pack
import game_assets

def gradle(mc,*tasks):
    paths=','.join(str(java_home(v)) for v in (17,21,25))
    subprocess.run([str(ROOT/'gradlew.bat'),'-p','neoforge','-Pmc='+mc,'-Porg.gradle.java.installations.paths='+paths,*tasks,'--console=plain',*proxy_args()],cwd=ROOT,check=True)

def audit(mc):
    spec=VERSIONS[mc];jar=ROOT/'build/neoforge'/mc/'libs'/('enzyme-neoforge-'+mc+'-'+MOD_VERSION+'.jar')
    assert jar.is_file()
    generate(mc,audit=True);gradle(mc,'probeJar')
    profile,info=install(mc)
    assets=game_assets.ensure(CACHE/'caches/fabric-loom',info)
    run=ROOT/'build/neoforge-production-runs'/mc/('run-'+str(time.time_ns()));(run/'mods').mkdir(parents=True)
    natives=run/'natives';natives.mkdir()
    items=[]
    for item in info['libraries']+profile['libraries']:
        if not allowed(item):continue
        if 'artifact' in item.get('downloads',{}):items.append(item)
        classifier=item.get('natives',{}).get('windows')
        if classifier:
            classifier=classifier.replace('${arch}','64');entry=item.get('downloads',{}).get('classifiers',{}).get(classifier)
            if entry:items.append(dict(name=item['name']+':'+classifier,downloads=dict(artifact=entry)))
    unique={}
    for item in items:
        parts=item['name'].split(':');unique[(parts[0],parts[1],tuple(parts[3:]))]=item
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:libs=list(pool.map(library,unique.values()))
    for lib in libs:
        if 'natives-windows' in lib.name:
            with zipfile.ZipFile(lib) as z:
                for name in z.namelist():
                    if name.endswith('.dll'):(natives/Path(name).name).write_bytes(z.read(name))
    shutil.copyfile(jar,run/'mods'/jar.name)
    probe=ROOT/'build/neoforge'/mc/'libs'/('enzyme-neoforge-probe-'+mc+'.jar');shutil.copyfile(probe,run/'mods'/probe.name)
    (run/'options.txt').write_text('onboardAccessibility:false\n')
    # ModLauncher locates the patched client itself. Adding the vanilla client
    # under its inherited filename creates a second JPMS Minecraft module.
    clients=[] if profile['mainClass'].startswith('cpw.mods.bootstraplauncher.') else [HOME/'versions'/mc/(mc+'.jar')]
    classpath=os.pathsep.join(str(p) for p in libs+clients)
    variables=dict(library_directory=str(HOME/'libraries'),classpath=classpath,classpath_separator=os.pathsep,natives_directory=str(natives),launcher_name='EnzymeAudit',launcher_version='1',version_name=profile['id'],game_directory=str(run),assets_root=str(CACHE/'caches/fabric-loom/assets'),assets_index_name=info['assetIndex']['id'],auth_player_name='EnzymeAudit',auth_uuid='00000000000000000000000000000000',auth_access_token='0',user_type='legacy',version_type='release',user_properties='{}',auth_xuid='0',clientid='0')
    def expand(value):
        for key,replacement in variables.items():value=value.replace('${'+key+'}',replacement)
        assert '${' not in value,value
        return value
    def arguments(items):
        result=[]
        for item in items:
            if isinstance(item,str):result.append(expand(item))
            elif allowed(item):result.extend(expand(v) for v in (item['value'] if isinstance(item['value'],list) else [item['value']]))
        return result
    jvm=arguments(info['arguments']['jvm']+profile.get('arguments',{}).get('jvm',[]))
    game=arguments(info['arguments']['game']+profile.get('arguments',{}).get('game',[]))
    java=java_home(spec['java'])/'bin/java.exe'
    command=[str(java),'-Xmx2G',*jvm,profile['mainClass'],*game]
    log=run/'launcher.log'
    print('NeoForge production JAR audit '+mc,flush=True)
    with log.open('w',encoding='utf8') as out:
        process=subprocess.Popen(command,cwd=run,stdout=out,stderr=subprocess.STDOUT)
        try:result=process.wait(timeout=420 if os.environ.get('PACKMOD_AUDIT_REAL_PACK') else 300)
        except subprocess.TimeoutExpired:
            process.terminate();process.wait(timeout=10);raise RuntimeError('NeoForge audit timed out: '+str(log))
    if result or not (run/'standalone-pass.txt').is_file():
        print(log.read_text(encoding='utf8',errors='replace')[-16000:]);raise RuntimeError('NeoForge production audit failed: '+str(log))
    target=json.loads((run/'conversion-target-check.json').read_text())
    assert target['passed'] and target['running_minecraft']==mc and target['major']==spec['format'] and target['loader']=='neoforge'
    origin=(run/'production-origin.txt').read_text();assert jar.name in origin,origin
    assert hashlib.sha256((run/'mods'/jar.name).read_bytes()).hexdigest()==hashlib.sha256(jar.read_bytes()).hexdigest()
    clarity=run/'ui-clarity-check';clarity.mkdir()
    subprocess.run([str(java.with_name('javac.exe')),'-d',str(clarity),str(ROOT/'tools/game/UiClarityCheck.java')],check=True)
    screenshots=['checklist','all-compatible','conversion-options','conversion-preview','conversion-history','report-details']
    subprocess.run([str(java),'-cp',str(clarity),'UiClarityCheck',*(str(run/'screenshots'/('standalone-'+name+'.png')) for name in screenshots)],check=True)
    record=dict(minecraft=mc,neoforge=spec['neoforge'],jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),production_audit_directory=str(run),production_jar_audit_passed=True,ui_clarity_passed=True,conversion_target_check=target,production_origin=origin)
    record['official_assets']=assets
    sample=real_pack.verify(run)
    if sample:record['real_pack_check']=sample
    (run.parent/'verification.json').write_text(json.dumps(record,indent=2)+'\n')
    print('NEOFORGE PRODUCTION JAR AUDIT PASS '+mc,flush=True)
    return record

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('versions',nargs='+',choices=VERSIONS);parser.add_argument('--real-pack');args=parser.parse_args();real_pack.configure(args.real_pack)
    for mc in args.versions:audit(mc)
