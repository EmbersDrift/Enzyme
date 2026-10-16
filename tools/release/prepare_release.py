"""Collect configured MODs and publish only allowlisted, path-free evidence."""
from pathlib import Path
import argparse, hashlib, json, shutil, struct, subprocess, sys, zipfile
import importlib.util

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'compat'))
from generate import VERSIONS, MOD_VERSION
from build_all import verify
from verify_targets import verify as verify_targets


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-only', action='store_true', help='CI compilation evidence; game audits stay false.')
    args = parser.parse_args()
    targets = verify_targets()
    destination = ROOT / 'build/github-release' / MOD_VERSION
    destination.mkdir(parents=True, exist_ok=True)
    records = json.loads((ROOT / 'build/compat-delivery/verification.json').read_text())['versions']
    assert len(records) == len(VERSIONS) and {r['minecraft'] for r in records} == set(VERSIONS)
    public = []
    files = []
    for record in records:
        source = ROOT / 'build/compat-delivery' / record['jar']
        assert source.parent == ROOT / 'build/compat-delivery'
        assert verify(record['minecraft'], source)['sha256'] == record['sha256']
        assert record['native_upgrade_downgrade_check_passed']
        if not args.build_only:
            assert record['production_jar_audit_passed'] and record['ui_clarity_passed']
            proof = json.loads((ROOT / 'build/compat-production-runs' / record['minecraft'] / 'verification.json').read_text())
            assert proof['jar_sha256'] == record['sha256']
            run = Path(proof['production_audit_directory']).resolve()
            assert run.is_relative_to((ROOT / 'build/compat-production-runs' / record['minecraft']).resolve())
            marker = (run / 'standalone-pass.txt').read_text()
            assert marker.startswith('PASS:') and 'native incompatible prompt' in marker and 'data pack exclusion' in marker
            target_check=json.loads((run/'conversion-target-check.json').read_text())
            assert target_check['passed'] and target_check['running_minecraft']==record['minecraft'] and target_check['major']==record['format']
        public.append({key: record[key] for key in ('minecraft', 'java', 'format', 'fabric_loader_tested',
            'fabric_api', 'jar', 'sha256', 'native_upgrade_downgrade_check_passed', 'game_audit_passed', 'production_jar_audit_passed')})
        public[-1]['ui_clarity_passed'] = record.get('ui_clarity_passed', False)
        public[-1]['development_client_audit_passed'] = public[-1].pop('game_audit_passed')
        public[-1]['loader']='fabric'
        public[-1]['runtime_conversion_target_verified']=not args.build_only
        target = destination / source.name
        shutil.copyfile(source, target)
        files.append(target)
    source = ROOT / ('build/libs/enzyme-26.2-' + MOD_VERSION + '.jar')
    with zipfile.ZipFile(source) as archive:
        meta = json.loads(archive.read('fabric.mod.json'))
        assert meta['name'] == 'Enzyme' and meta['version'] == MOD_VERSION and meta['license'] == 'MIT'
        assert meta['depends']['minecraft'] == '~26.2' and meta['depends']['java'] == '>=25'
        assert archive.read('LICENSE') == (ROOT / 'LICENSE').read_bytes()
        assert b'PackStandaloneAudit' not in archive.read('com/embers/packmod/PackModClient.class')
        for name in archive.namelist():
            assert not any(x in name for x in ('PackStandaloneAudit', 'PackConversionCheck', 'ProductionProbe'))
            if name.endswith('.class'):
                assert struct.unpack('>H', archive.read(name)[6:8])[0] <= 69
        manifest = json.loads(archive.read('tools/pyramid/windows-x86_64/runner.json'))
        assert hashlib.sha256(archive.read('tools/pyramid/windows-x86_64/embers-pyramid.exe')).hexdigest() == manifest['binary_sha256']
        assert hashlib.sha256(archive.read('tools/pyramid/uimage.zip')).hexdigest() == manifest['templates_sha256']
        assert archive.read('NOTICE.md') == (ROOT / 'NOTICE.md').read_bytes()
        assert json.loads(archive.read('assets/packmod/conversion-target.json'))==dict(minecraft='26.2',major=88,minor=0,loader='fabric')
    game_pass = False
    if not args.build_only:
        proof=json.loads((ROOT/'build/compat-production-runs/26.2/verification.json').read_text())
        assert proof['production_jar_audit_passed'] and proof['jar_sha256']==sha(source) and proof['ui_clarity_passed']
        run=Path(proof['production_audit_directory']).resolve()
        assert run.is_relative_to((ROOT/'build/compat-production-runs/26.2').resolve())
        assert (run / 'standalone-pass.txt').read_text().startswith('PASS:')
        bundles = list((run / 'packmod-tools/pyramid/jobs').glob('*/bundle-manifest.json'))
        assert bundles, 'Missing native engine evidence in the root game audit'
        assert all(json.loads(p.read_text())['binary_sha256'] == manifest['binary_sha256'] for p in bundles), 'Root game audit used a different core'
        game_pass = True
        target_check=json.loads((run/'conversion-target-check.json').read_text())
        assert target_check['passed'] and target_check['running_minecraft']=='26.2' and target_check['major']==88
        assert all((run/'screenshots'/('standalone-'+name+'.png')).is_file() for name in
                   ['checklist','all-compatible','conversion-options','conversion-preview','conversion-history','report-details'])
    public.append(dict(minecraft='26.2', java=25, format=88, fabric_loader_tested='0.19.5', fabric_api='0.161.0+26.2',
        jar=source.name, sha256=sha(source), native_conversion_check_passed=True, development_client_audit_passed=False,
        production_jar_integrity_passed=True,production_jar_audit_passed=game_pass,loader='fabric',runtime_conversion_target_verified=game_pass,ui_clarity_passed=game_pass))
    target = destination / source.name
    shutil.copyfile(source, target)
    files.append(target)
    # Load the separate loader project without confusing its generator with Fabric's.
    spec=importlib.util.spec_from_file_location('enzyme_neoforge_artifacts',ROOT/'neoforge/artifacts.py')
    neo=importlib.util.module_from_spec(spec);spec.loader.exec_module(neo)
    neo_records=json.loads((ROOT/'build/neoforge-delivery/verification.json').read_text())['versions']
    assert {r['minecraft'] for r in neo_records}==set(neo.VERSIONS) and len(neo_records)==len(neo.VERSIONS)
    for record in neo_records:
        source=ROOT/'build/neoforge-delivery'/record['jar']
        assert source.parent==ROOT/'build/neoforge-delivery'
        assert neo.verify(record['minecraft'],source)['sha256']==record['sha256']
        if not args.build_only:
            assert record['production_jar_audit_passed'] and record['ui_clarity_passed']
            proof=json.loads((ROOT/'build/neoforge-production-runs'/record['minecraft']/'verification.json').read_text())
            assert proof['jar_sha256']==record['sha256'] and proof['conversion_target_check']['running_minecraft']==record['minecraft']
            run=Path(proof['production_audit_directory']).resolve()
            assert run.is_relative_to((ROOT/'build/neoforge-production-runs'/record['minecraft']).resolve())
            assert (run/'standalone-pass.txt').read_text().startswith('PASS:')
        public.append({key:record[key] for key in ('minecraft','loader','neoforge_tested','java','format','jar','sha256','native_upgrade_downgrade_check_passed')})
        public[-1].update(production_jar_audit_passed=not args.build_only,ui_clarity_passed=not args.build_only,runtime_conversion_target_verified=not args.build_only)
        target=destination/source.name;shutil.copyfile(source,target);files.append(target)
    # Optional actual-pack results are emitted only when bound to every shipped JAR.
    # Never copy source names, converted files, paths, reports or game logs.
    sample_matrices=[ROOT/'build/real-pack-audit'/('matrix-'+loader+'.json') for loader in ('fabric','neoforge')]
    if not args.build_only and all(p.is_file() for p in sample_matrices):
        matrices=[json.loads(p.read_text()) for p in sample_matrices]
        assert all(m['version']==MOD_VERSION for m in matrices)
        samples=[r for m in matrices for r in m['versions']]
        assert len(samples)==len(public) and {(r['loader'],r['minecraft']) for r in samples}=={(r['loader'],r['minecraft']) for r in public}
        for record in public:
            sample=next(r for r in samples if (r['loader'],r['minecraft'])==(record['loader'],record['minecraft']))
            assert sample['jar_sha256']==record['sha256'] and sample['resource_reload_passed']
            proof_root='compat-production-runs' if record['loader']=='fabric' else 'neoforge-production-runs'
            proof=json.loads((ROOT/'build'/proof_root/record['minecraft']/'verification.json').read_text())
            assert proof['real_pack_check']['source_sha256']==sample['source_sha256'] and proof['real_pack_check']['resource_reload_passed']
            record['real_pack_regression']=dict(source_sha256=sample['source_sha256'],presets=sample['presets'],resource_reload_passed=True)
    baseline = json.loads((ROOT / 'tools/upstream/core-baseline.json').read_text())
    assert manifest['core_version'] == baseline['core_version'] and manifest['upstream_commit'] == baseline['upstream_commit']
    assert manifest['core_baseline_sha256'] == sha(ROOT / 'tools/upstream/core-baseline.json')
    for name, digest in baseline['files'].items():
        assert sha(ROOT / name) == digest
    evidence = dict(project='Enzyme', version=MOD_VERSION, license='MIT', platform='windows-x86_64',
        source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        source_tree_dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
        core_version=baseline['core_version'], core_changed=bool(baseline.get('previous_core_version')),core_changed_this_loader_update=False,core_baseline_verified=True, frozen_files=len(baseline['files']),
        previous_core_version=baseline.get('previous_core_version'), upstream_version=baseline.get('upstream_version'), engine=manifest['capabilities'].get('engine'),
        upstream_commit=baseline['upstream_commit'], binary_sha256=manifest['binary_sha256'],
        validation='build-only' if args.build_only else 'local-build-and-game-audits', official_conversion_targets=targets,versions=public)
    report = destination / 'release-validation.json'
    report.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    files.append(report)
    sample_summary=ROOT/'build/real-pack-audit/summary.json'
    if not args.build_only and sample_summary.is_file():
        sample=json.loads(sample_summary.read_text())
        assert sample['passed'] and sample['version']==MOD_VERSION and sample['builds']==len(public)
        assert {(r['loader'],r['minecraft'],r['jar_sha256']) for r in sample['versions']}=={(r['loader'],r['minecraft'],r['sha256']) for r in public}
        target=destination/'real-pack-validation.json';shutil.copyfile(sample_summary,target);files.append(target)
    for name in ('LICENSE', 'NOTICE.md', 'README.md', 'TESTING.md', 'RELEASE-' + MOD_VERSION + '.md',
                 'tools/upstream/LOCAL-PATCHES.md', 'tools/upstream/import-manifest.json','neoforge/README.md'):
        target = destination / ('NEOFORGE.md' if name=='neoforge/README.md' else Path(name).name)
        shutil.copyfile(ROOT / name, target)
        if name in ('README.md','TESTING.md'):
            target.write_bytes(target.read_text(encoding='utf8').replace('(neoforge/README.md)','(NEOFORGE.md)').encode('utf8'))
        files.append(target)
    archive_path = destination / ('enzyme-' + MOD_VERSION + '-fabric-neoforge-all-versions.zip')
    with zipfile.ZipFile(archive_path, 'w') as archive:
        for path in files:
            archive.writestr(zipfile.ZipInfo(path.name, (2000, 1, 1, 0, 0, 0)), path.read_bytes())
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.testzip() is None and len(archive.namelist()) == len(files)
    files.append(archive_path)
    checksums = destination / 'SHA256SUMS.txt'
    checksums.write_text(''.join(sha(path) + '  ' + path.name + '\n' for path in files), encoding='utf-8')
    files.append(checksums)
    subprocess.run([sys.executable, str(ROOT / 'tools/release/audit_upload.py'), '--history-ref', 'HEAD',
                    '--assets', *map(str, files), '--report', str(ROOT / 'build/publish-audit/release-audit.json')], cwd=ROOT, check=True)
    print(str(len(public)) + '-version release ready: ' + str(destination))


if __name__ == '__main__':
    main()
