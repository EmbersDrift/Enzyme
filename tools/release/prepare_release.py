"""Collect six checked MODs and publish only allowlisted, path-free evidence."""
from pathlib import Path
import argparse, hashlib, json, shutil, struct, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'compat'))
from generate import VERSIONS
from build_all import verify


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-only', action='store_true', help='CI compilation evidence; game audits stay false.')
    args = parser.parse_args()
    destination = ROOT / 'build/github-release'
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
            assert record['game_audit_passed'] and record['production_jar_audit_passed']
            proof = json.loads((ROOT / 'build/compat-production-runs' / record['minecraft'] / 'verification.json').read_text())
            assert proof['jar_sha256'] == record['sha256']
        public.append({key: record[key] for key in ('minecraft', 'java', 'format', 'fabric_loader_tested',
            'fabric_api', 'jar', 'sha256', 'native_upgrade_downgrade_check_passed', 'game_audit_passed', 'production_jar_audit_passed')})
        target = destination / source.name
        shutil.copyfile(source, target)
        files.append(target)
    source = ROOT / 'build/libs/enzyme-26.2-0.4.0.jar'
    with zipfile.ZipFile(source) as archive:
        meta = json.loads(archive.read('fabric.mod.json'))
        assert meta['name'] == 'Enzyme' and meta['version'] == '0.4.0' and meta['license'] == 'MIT'
        assert meta['depends']['minecraft'] == '~26.2' and meta['depends']['java'] == '>=25'
        assert archive.read('LICENSE') == (ROOT / 'LICENSE').read_bytes()
        assert b'PackStandaloneAudit' not in archive.read('com/embers/packmod/PackModClient.class')
        for name in archive.namelist():
            assert not any(x in name for x in ('PackStandaloneAudit', 'PackConversionCheck', 'ProductionProbe'))
            if name.endswith('.class'):
                assert struct.unpack('>H', archive.read(name)[6:8])[0] <= 69
        manifest = json.loads(archive.read('tools/pyramid/windows-x86_64/runner.json'))
        assert hashlib.sha256(archive.read('tools/pyramid/windows-x86_64/embers-pyramid.exe')).hexdigest() == manifest['binary_sha256']
    game_pass = False
    if not args.build_only:
        run = Path((ROOT / 'build/last-game-audit.txt').read_text()).resolve()
        assert run.is_relative_to((ROOT / 'build').resolve())
        assert (run / 'standalone-pass.txt').read_text().startswith('PASS:')
        game_pass = True
    public.append(dict(minecraft='26.2', java=25, format=88, fabric_loader_tested='0.19.5', fabric_api='0.161.0+26.2',
        jar=source.name, sha256=sha(source), native_conversion_check_passed=True, game_audit_passed=game_pass,
        production_jar_integrity_passed=True))
    target = destination / source.name
    shutil.copyfile(source, target)
    files.append(target)
    baseline = json.loads((ROOT / 'tools/upstream/core-baseline.json').read_text())
    for name, digest in baseline['files'].items():
        assert sha(ROOT / name) == digest
    evidence = dict(project='Enzyme', version='0.4.0', license='MIT', platform='windows-x86_64',
        source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        core_version=baseline['core_version'], core_changed=False, frozen_files=len(baseline['files']),
        upstream_commit=baseline['upstream_commit'], binary_sha256=manifest['binary_sha256'],
        validation='build-only' if args.build_only else 'local-build-and-game-audits', versions=public)
    report = destination / 'release-validation.json'
    report.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    files.append(report)
    for name in ('LICENSE', 'NOTICE.md', 'README.md', 'TESTING.md'):
        target = destination / name
        shutil.copyfile(ROOT / name, target)
        files.append(target)
    archive_path = destination / 'enzyme-0.4.0-fabric-all-6-versions.zip'
    with zipfile.ZipFile(archive_path, 'w') as archive:
        for path in files:
            archive.writestr(zipfile.ZipInfo(path.name, (2000, 1, 1, 0, 0, 0)), path.read_bytes())
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.testzip() is None and len(archive.namelist()) == 11
    files.append(archive_path)
    checksums = destination / 'SHA256SUMS.txt'
    checksums.write_text(''.join(sha(path) + '  ' + path.name + '\n' for path in files), encoding='utf-8')
    files.append(checksums)
    subprocess.run([sys.executable, str(ROOT / 'tools/release/audit_upload.py'), '--history-ref', 'HEAD',
                    '--assets', *map(str, files), '--report', str(ROOT / 'build/publish-audit/release-audit.json')], cwd=ROOT, check=True)
    print('Six-version release ready: ' + str(destination))


if __name__ == '__main__':
    main()
