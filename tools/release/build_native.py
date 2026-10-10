"""Rebuild the frozen core without embedding the builder's home/workspace paths."""
from pathlib import Path
import json, os, subprocess

ROOT = Path(__file__).resolve().parents[2]
env = os.environ.copy()
flags = env.get('CARGO_ENCODED_RUSTFLAGS', '')
if not flags and env.get('RUSTFLAGS'):
    raise RuntimeError('Use CARGO_ENCODED_RUSTFLAGS rather than ambiguous shell RUSTFLAGS for this release build.')
remaps = ['--remap-path-prefix=' + str(Path.home()) + '=/builder-home',
          '--remap-path-prefix=' + str(ROOT) + '=/enzyme']
env['CARGO_ENCODED_RUSTFLAGS'] = '\x1f'.join(([flags] if flags else []) + remaps)
baseline = json.loads((ROOT / 'tools/upstream/core-baseline.json').read_text())
import hashlib
for name, digest in baseline['files'].items():
    assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest
subprocess.run(['cargo', 'build', '--release', '--locked', '--manifest-path', 'pyramid-bridge/Cargo.toml'],
               cwd=ROOT, env=env, check=True)
print('Frozen core rebuilt; builder home and workspace paths remapped.')
