"""Build and convert a private sample under every supported loader/runtime.

Use --clients for isolated production-client reload and UI audits (requires a desktop).
Input/output packs and private reports stay in the ignored build directory.
"""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,sys,time,urllib.request,urllib.parse

ROOT=Path(__file__).resolve().parents[2]
VERSION=next(v.split('=',1)[1] for v in (ROOT/'gradle.properties').read_text().splitlines() if v.startswith('version='))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('source',type=Path)
    parser.add_argument('--loader',choices=['all','fabric','neoforge'],default='all')
    parser.add_argument('--versions',nargs='+');parser.add_argument('--clients',action='store_true');parser.add_argument('--skip-root',action='store_true')
    parser.add_argument('--report-name',help='Optional separate matrix filename for an independent subset.')
    args=parser.parse_args();source=args.source.resolve();assert source.is_file()
    before=hashlib.sha256(source.read_bytes()).hexdigest();env=os.environ.copy()
    if args.clients:env['PACKMOD_AUDIT_REAL_PACK']=str(source)
    work=ROOT/'build/real-pack-audit';work.mkdir(parents=True,exist_ok=True)
    report_name=args.report_name or 'matrix-'+args.loader+'.json'
    assert Path(report_name).name==report_name and report_name.endswith('.json')
    record_path=work/report_name;records=[]
    sys.path.insert(0,str(ROOT/'compat'));from toolchains import installation_paths
    paths=installation_paths()
    proxy=urllib.request.getproxies().get('https');proxy_flags=[]
    if proxy:
        value=urllib.parse.urlparse(proxy)
        for protocol in ('http','https'):proxy_flags.extend(['-D'+protocol+'.proxyHost='+value.hostname,'-D'+protocol+'.proxyPort='+str(value.port)])
    for loader in ('fabric','neoforge'):
        if args.loader not in ('all',loader):continue
        config=json.loads((ROOT/('compat' if loader=='fabric' else 'neoforge')/'versions.json').read_text())
        if loader=='fabric':config={'26.2':dict(format=88,java=25),**config}
        for mc,spec in config.items():
            if args.skip_root and loader=='fabric' and mc=='26.2':continue
            if args.versions and mc not in args.versions:continue
            project=None if loader=='fabric' and mc=='26.2' else ('compat' if loader=='fabric' else 'neoforge')
            print('REAL PACK '+loader+' '+mc,flush=True)
            if project:subprocess.run([sys.executable,str(ROOT/project/'generate.py'),mc],cwd=ROOT,env=env,check=True)
            build=ROOT/('build/'+project+'/'+mc if project else 'build')
            stamp=time.time_ns();log=work/(loader+'-'+mc+'-native.log')
            cmd=[str(ROOT/'gradlew.bat')]
            if project:cmd+=['-p',project,'-Pmc='+mc,'clean']
            cmd+=['build','userPackCheck','-I',str(ROOT/'tools/regressions/user-pack-check.init.gradle'),'-PuserPackSource='+str(source),'-PuserPackTarget='+str(spec['format']),'-PuserPackPreset=all','-Porg.gradle.java.installations.paths='+paths,'--console=plain',*proxy_flags]
            with log.open('w',encoding='utf8') as out:result=subprocess.run(cmd,cwd=ROOT,env=env,stdout=out,stderr=subprocess.STDOUT)
            if result.returncode:raise RuntimeError('Build/native check failed; inspect '+str(log))
            checks=[json.loads(p.read_text('utf8')) for p in (build/'user-pack-check').glob('run-*/verification.json') if p.stat().st_mtime_ns>=stamp]
            assert {p['preset'] for p in checks}=={'conservative','defaults','complete'} and len(checks)==3
            jar=build/'libs'/('enzyme-'+('neoforge-' if loader=='neoforge' else '')+mc+'-'+VERSION+'.jar');assert jar.is_file()
            if loader=='fabric' and project:
                delivery=ROOT/'build/compat-delivery';delivery.mkdir(exist_ok=True);shutil.copyfile(jar,delivery/jar.name)
            record=dict(minecraft=mc,loader=loader,format=spec['format'],source_sha256=before,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),presets={p['preset']:dict(decoded_images=p['decoded_images'],preserved_audio=p['preserved_audio'],modern_item_definitions=p['modern_item_definitions'],warning_codes=sorted({w['code'] for w in p['warnings']}),repair_codes=sorted({r['code'] for r in p.get('repairs',[])})) for p in checks})
            if args.clients:
                audit=ROOT/(('neoforge' if loader=='neoforge' else 'compat')+'/production_audit.py')
                command=[sys.executable,str(audit),mc]
                game_log=work/(loader+'-'+mc+'-client.log')
                with game_log.open('w',encoding='utf8') as out:result=subprocess.run(command,cwd=ROOT,env=env,stdout=out,stderr=subprocess.STDOUT)
                if result.returncode:raise RuntimeError('Actual client reload failed; inspect '+str(game_log))
                record['resource_reload_passed']=True
            assert before==hashlib.sha256(source.read_bytes()).hexdigest()
            records.append(record);record_path.write_text(json.dumps(dict(version=VERSION,source_sha256=before,versions=records),indent=2)+'\n',encoding='utf8')
            print('REAL PACK PASS '+loader+' '+mc+' (3 presets'+', resource reload'*(args.clients)+')',flush=True)


if __name__=='__main__':main()
