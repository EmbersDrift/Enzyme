"""Install official NeoForge into an isolated build directory for production audits."""
from pathlib import Path
import concurrent.futures,hashlib,json,os,re,shutil,subprocess,urllib.request,urllib.parse,zipfile
from generate import ROOT,VERSIONS

CACHE=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))
HOME=ROOT/'build/neoforge-launcher'

def proxy_args():
    args=[]
    for kind,url in urllib.request.getproxies().items():
        if kind not in ('http','https'):continue
        parsed=urllib.parse.urlparse(url)
        if parsed.hostname and not parsed.username:
            args += ['-D'+kind+'.proxyHost='+parsed.hostname,'-D'+kind+'.proxyPort='+str(parsed.port or 80)]
    return args

def java_home(major):
    candidates=[]
    for key in ('PACKMOD_JAVA'+str(26 if major==25 else major)+'_HOME','JAVA_HOME_'+str(major)+'_X64','JAVA_HOME'):
        if os.environ.get(key):candidates.append(Path(os.environ[key]))
    cache=Path(os.environ.get('LOCALAPPDATA',str(Path.home()/'.cache')))/'Enzyme/jdks'/str(major)
    candidates.extend(p.parent.parent for p in cache.glob('*/bin/java.exe'))
    executable=shutil.which('java')
    if executable:
        settings=subprocess.run([executable,'-XshowSettings:properties','-version'],capture_output=True,text=True,errors='replace').stderr
        actual=re.search(r'java.home\s*=\s*(.+)',settings)
        if actual:candidates.append(Path(actual[1].strip()))
    accepted=(25,26) if major==25 else (major,)
    for home in candidates:
        java=home/'bin/java.exe'
        if not java.is_file():continue
        settings=subprocess.run([str(java),'-XshowSettings:properties','-version'],capture_output=True,text=True,errors='replace').stderr
        if any(re.search(r'java.specification.version\s*=\s*'+str(v)+r'\s',settings) for v in accepted) and re.search(r'os.arch\s*=\s*(amd64|x86_64)\s',settings):return home.resolve()
    raise RuntimeError('Set PACKMOD_JAVA'+str(26 if major==25 else major)+'_HOME to the matching x64 JDK')


def coordinate(name):
    group,artifact,version,*classifier=name.split(':')
    filename=artifact+'-'+version+('-'+classifier[0] if classifier else '')+'.jar'
    return group,artifact,version,filename,group.replace('.','/')+'/'+artifact+'/'+version+'/'+filename

def allowed(item):
    rules=item.get('rules');value=rules is None
    for rule in rules or []:
        system=rule.get('os',{})
        if system.get('name','windows')!='windows' or system.get('arch','amd64') not in {'amd64','x86_64'} or rule.get('features'):continue
        value=rule['action']=='allow'
    return value

def library(item):
    group,artifact,version,filename,path=coordinate(item['name'])
    entry=item.get('downloads',{}).get('artifact',{})
    path=entry.get('path',path);dest=HOME/'libraries'/path;dest.parent.mkdir(parents=True,exist_ok=True)
    digest=entry.get('sha1')
    def valid(p):return p.is_file() and (not digest or hashlib.sha1(p.read_bytes()).hexdigest()==digest)
    if valid(dest):return dest
    candidates=list((CACHE/'caches/modules-2/files-2.1'/group/artifact/version).glob('*/'+filename))
    candidates += [ROOT/'build/compat-production-libraries'/path]
    found=next((p for p in candidates if valid(p)),None)
    if found:shutil.copyfile(found,dest)
    elif entry.get('url'):
        pending=dest.with_suffix('.jar.tmp')
        for attempt in range(3):
            try:
                urllib.request.urlretrieve(entry['url'],pending)
                assert valid(pending),'Library hash mismatch: '+item['name']
                pending.replace(dest);break
            except Exception:
                if attempt==2:raise
    else:return dest # Installer-generated or embedded artifact.
    assert valid(dest),'Library hash mismatch: '+item['name']
    return dest

def install(mc):
    spec=VERSIONS[mc];v=spec['neoforge'];artifact='forge' if spec.get('legacy') else 'neoforge'
    research=ROOT/'build/neoforge-research';research.mkdir(exist_ok=True)
    installer=research/(v+'-installer.jar')
    if not installer.is_file():urllib.request.urlretrieve(f'https://maven.neoforged.net/releases/net/neoforged/{artifact}/{v}/{artifact}-{v}-installer.jar',installer)
    with zipfile.ZipFile(installer) as z:
        profile=json.loads(z.read('version.json'));instructions=json.loads(z.read('install_profile.json'))
    assert profile['inheritsFrom']==mc,(mc,'NeoForge installer targets another Minecraft')
    info=json.loads((CACHE/'caches/fabric-loom'/mc/'mojang_minecraft_info.json').read_text())
    vanilla=HOME/'versions'/mc;vanilla.mkdir(parents=True,exist_ok=True)
    (vanilla/(mc+'.json')).write_text(json.dumps(info))
    source=CACHE/'caches/fabric-loom'/mc/'minecraft-client.jar'
    assert hashlib.sha1(source.read_bytes()).hexdigest()==info['downloads']['client']['sha1']
    if not (vanilla/(mc+'.jar')).is_file():shutil.copyfile(source,vanilla/(mc+'.jar'))
    (HOME/'launcher_profiles.json').write_text('{"profiles":{}}')
    items=[l for l in info['libraries']+profile['libraries']+instructions.get('libraries',[]) if allowed(l)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:list(pool.map(library,items))
    marker=HOME/'versions'/profile['id']/(profile['id']+'.json')
    if not marker.is_file():
        args=[str(java_home(spec['java'])/'bin/java.exe'),*proxy_args(),'-Djava.awt.headless=true','-jar',str(installer),'--installClient',str(HOME)]
        with (research/(mc+'-installation.log')).open('w',encoding='utf8') as output:
            subprocess.run(args,cwd=research,stdout=output,stderr=subprocess.STDOUT,check=True,timeout=600)
    assert marker.is_file()
    return json.loads(marker.read_text()),info
