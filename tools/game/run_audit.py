"""Temporarily stage the isolated game audit, restoring production sources even on failure."""
from pathlib import Path
import subprocess
import time

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
    result = subprocess.run([str(root / 'gradlew.bat'), 'runClient', '--console=plain'], cwd=root)
    if result.returncode:
        raise SystemExit(result.returncode)
    marker = run_dir / 'standalone-pass.txt'
    if not marker.exists():
        raise SystemExit(f'Game audit did not pass; inspect {run_dir / "logs/latest.log"}')
    (root / 'build/last-game-audit.txt').write_text(str(run_dir), encoding='utf-8')
    print(marker.read_text())
finally:
    entry.write_bytes(original_entry)
    build.write_bytes(original_build)
    staged.unlink(missing_ok=True)
