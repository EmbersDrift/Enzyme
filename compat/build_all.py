"""Reproducible per-version build, optional UI audit, and checked delivery directory."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,struct,subprocess,sys,zipfile
from generate import ROOT,VERSIONS,generate
from toolchains import installation_paths

def verify(mc,jar):
    spec=VERSIONS[mc]
    baseline_path=ROOT/'tools/upstream/core-baseline.json'
    baseline=json.loads(baseline_path.read_text())
    with zipfile.ZipFile(jar) as z:
        meta=json.loads(z.read('fabric.mod.json'))
        assert meta['depends']['minecraft']==mc and meta['depends']['java']=='>='+str(spec['java'])
        assert meta['version']=='0.4.0'
        assert meta['name']=='Enzyme' and meta['license']=='MIT' and 'LICENSE' in z.namelist()
        assert z.read('LICENSE')==(ROOT/'LICENSE').read_bytes()
        for name in z.namelist():
            assert 'PackStandaloneAudit' not in name and 'VersionConversionCheck' not in name and 'ProductionProbe' not in name
            if name.endswith('.class'):
                data=z.read(name)
                assert struct.unpack('>H',data[6:8])[0]<=spec['java']+44,(mc,name,'bytecode too new')
        assert b'PackStandaloneAudit' not in z.read('com/embers/packmod/PackModClient.class')
        manifest=json.loads(z.read('tools/pyramid/windows-x86_64/runner.json'))
        assert manifest['core_version']==baseline['core_version'] and manifest['upstream_commit']==baseline['upstream_commit']
        assert manifest['core_baseline_sha256']==hashlib.sha256(baseline_path.read_bytes()).hexdigest()
        assert manifest['capabilities']['core_version']==baseline['core_version'] and manifest['capabilities']['schema_version']==baseline['protocol_version']
        assert any(t['major']==spec['format'] for t in manifest['capabilities']['targets'])
        assert hashlib.sha256(z.read('tools/pyramid/windows-x86_64/embers-pyramid.exe')).hexdigest()==manifest['binary_sha256']
        assert hashlib.sha256(z.read('tools/pyramid/uimage.zip')).hexdigest()==manifest['templates_sha256']
        assert json.loads(z.read('assets/packmod/lang/en_us.json')).keys()==json.loads(z.read('assets/packmod/lang/zh_cn.json')).keys()
        assert json.loads(z.read('packmod.client.mixins.json'))['compatibilityLevel']=='JAVA_'+str(spec['java'])
    return dict(minecraft=mc,java=spec['java'],format=spec['format'],fabric_loader_tested='0.19.5',fabric_api=spec['fabric_api'],jar=jar.name,sha256=hashlib.sha256(jar.read_bytes()).hexdigest())

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    game=parser.add_mutually_exclusive_group();game.add_argument('--audit',action='store_true');game.add_argument('--reuse-audit',action='store_true')
    production=parser.add_mutually_exclusive_group();production.add_argument('--production-audit',action='store_true');production.add_argument('--reuse-production-audit',action='store_true')
    parser.add_argument('--versions',nargs='+',choices=VERSIONS,default=list(VERSIONS));args=parser.parse_args()
    paths=installation_paths()
    env=os.environ.copy();env.setdefault('GRADLE_USER_HOME',str(Path.home()/'.gradle'))
    records=[];delivery=ROOT/'build/compat-delivery';delivery.mkdir(exist_ok=True)
    for mc in args.versions:
        if args.audit:subprocess.run([sys.executable,str(ROOT/'compat/run_audit.py'),mc],cwd=ROOT,env=env,check=True)
        generate(mc)
        # Audit/probe compilation shares the output directory. Clear incremental
        # leftovers before release rather than relying on deleted-source cleanup.
        subprocess.run([str(ROOT/'gradlew.bat'),'-p','compat','-Pmc='+mc,'-Porg.gradle.java.installations.paths='+paths,'clean','build','--console=plain'],cwd=ROOT,env=env,check=True)
        jar=ROOT/'build/compat'/mc/'libs'/('enzyme-'+mc+'-0.4.0.jar')
        record=verify(mc,jar);record.update(game_audit_passed=False,native_upgrade_downgrade_check_passed=True)
        if args.audit or args.reuse_audit:
            run=Path((ROOT/'build/compat-runs'/mc/'last-audit.txt').read_text()).resolve()
            assert run.is_relative_to((ROOT/'build/compat-runs'/mc).resolve())
            assert (run/'standalone-pass.txt').read_text().startswith('PASS:')
            record.update(game_audit_passed=True,game_audit_directory=str(run))
        shutil.copyfile(jar,delivery/jar.name)
        record['production_jar_audit_passed']=False
        if args.production_audit:
            subprocess.run([sys.executable,str(ROOT/'compat/production_audit.py'),mc],cwd=ROOT,env=env,check=True)
        if args.production_audit or args.reuse_production_audit:
            proof=json.loads((ROOT/'build/compat-production-runs'/mc/'verification.json').read_text())
            assert proof['jar_sha256']==record['sha256'],(mc,'Production audit was for a different JAR; run --production-audit again')
            run=Path(proof['production_audit_directory']).resolve()
            assert run.is_relative_to((ROOT/'build/compat-production-runs'/mc).resolve())
            assert proof['production_jar_audit_passed'] and (run/'standalone-pass.txt').read_text().startswith('PASS:')
            record.update(production_jar_audit_passed=True,production_audit_directory=str(run))
        records.append(record)
    (delivery/'verification.json').write_text(json.dumps(dict(core_version='0.2.2',core_changed=False,platform='windows-x86_64',versions=records),indent=2)+'\n')
    print('Checked delivery: '+str(delivery))
