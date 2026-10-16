"""Package all configured, verified production JARs, without audit probes."""
from pathlib import Path
import hashlib, json, shutil, zipfile
from generate import ROOT, VERSIONS, MOD_VERSION
from build_all import verify

delivery = ROOT / 'build/compat-delivery'
report = json.loads((delivery / 'verification.json').read_text())
records = report['versions']
assert len(records) == len(VERSIONS) and {r['minecraft'] for r in records} == set(VERSIONS)
baseline = json.loads((ROOT / 'tools/upstream/core-baseline.json').read_text())
assert report['core_version'] == baseline['core_version'] and report['core_baseline_verified'] is True
files = []
for record in records:
    jar = delivery / record['jar']
    assert jar.parent == delivery and verify(record['minecraft'], jar)['sha256'] == record['sha256']
    assert all(record[key] for key in ('game_audit_passed', 'production_jar_audit_passed', 'native_upgrade_downgrade_check_passed'))
    proof = json.loads((ROOT / 'build/compat-production-runs' / record['minecraft'] / 'verification.json').read_text())
    assert proof['jar_sha256'] == record['sha256']
    files.append(jar)
for source in (ROOT / 'compat/INSTALL.md', ROOT / 'TESTING.md'):
    shutil.copyfile(source, delivery / source.name)
files.extend(delivery / name for name in ('INSTALL.md', 'TESTING.md', 'verification.json'))
archive = delivery / ('enzyme-' + MOD_VERSION + '-fabric-' + str(len(VERSIONS)) + '-versions.zip')
temporary = archive.with_suffix('.zip.tmp')
with zipfile.ZipFile(temporary, 'w') as output:
    for path in files:
        entry = zipfile.ZipInfo(path.name, (2000, 1, 1, 0, 0, 0))
        output.writestr(entry, path.read_bytes())
with zipfile.ZipFile(temporary) as output:
    assert output.testzip() is None and len(output.namelist()) == len(files)
temporary.replace(archive)
print('Verified ' + str(len(VERSIONS)) + '-version archive: ' + str(archive))
print('SHA256: ' + hashlib.sha256(archive.read_bytes()).hexdigest())
