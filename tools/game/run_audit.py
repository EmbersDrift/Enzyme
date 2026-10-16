"""Temporarily stage the isolated game audit, restoring production sources even on failure."""
from pathlib import Path
import subprocess
import sys
import time
import os,shutil,json,hashlib
import real_pack

root = Path(__file__).resolve().parents[2]
entry = root / 'src/client/java/com/embers/packmod/PackModClient.java'
build = root / 'build.gradle'
staged = entry.parent / 'PackStandaloneAudit.java'
assert not staged.exists()
original_entry, original_build = entry.read_bytes(), build.read_bytes()
run_dir = root / 'build' / f'game-audit-{time.time_ns()}'
options = run_dir / 'options.txt'
options.parent.mkdir(parents=True, exist_ok=True)
text = options.read_text(encoding='utf-8') if options.exists() else ''
lines = [line for line in text.splitlines() if not line.startswith('onboardAccessibility:')]
options.write_text('\n'.join(lines + ['onboardAccessibility:false']) + '\n', encoding='utf-8')
try:
    staged.write_bytes((root / 'tools/game/PackStandaloneAudit.java').read_bytes())
    entry.write_text(original_entry.decode('utf-8').replace('        PackConversionManager.init();',
                     '        PackConversionManager.init();\n        PackStandaloneAudit.init();'), encoding='utf-8')
    build.write_text(original_build.decode('utf-8') + "\nloom { runs { client { runDir '" + run_dir.relative_to(root).as_posix() + "' } } }\n", encoding='utf-8')
    result = subprocess.run([str(root / 'gradlew.bat'), 'runClient', '--console=plain', *sys.argv[1:]], cwd=root)
    if result.returncode:
        raise SystemExit(result.returncode)
    marker = run_dir / 'standalone-pass.txt'
    if not marker.exists():
        raise SystemExit(f'Game audit did not pass; inspect {run_dir / "logs/latest.log"}')
    real_pack.verify(run_dir)
    clarity=run_dir/'ui-clarity-check';clarity.mkdir(exist_ok=True)
    compiler=str(Path(os.environ['JAVA_HOME'])/'bin/javac.exe') if os.environ.get('JAVA_HOME') else shutil.which('javac')
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe') if os.environ.get('JAVA_HOME') else shutil.which('java')
    subprocess.run([compiler,'-d',str(clarity),str(root/'tools/game/UiClarityCheck.java')],check=True)
    screenshots=[run_dir/'screenshots'/('standalone-'+name+'.png') for name in ['checklist','all-compatible','conversion-options','conversion-preview','conversion-history','report-details']]
    subprocess.run([java,'-cp',str(clarity),'UiClarityCheck',*map(str,screenshots)],check=True)
    (run_dir/'ui-clarity.json').write_text(json.dumps(dict(passed=True,screenshots={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in screenshots}),indent=2)+'\n')
    (root / 'build/last-game-audit.txt').write_text(str(run_dir), encoding='utf-8')
    print(marker.read_text())
finally:
    entry.write_bytes(original_entry)
    build.write_bytes(original_build)
    staged.unlink(missing_ok=True)
