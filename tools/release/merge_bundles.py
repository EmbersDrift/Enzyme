"""Merge native CI artifacts after checking shared template/baseline identities."""
from pathlib import Path
import hashlib,json,shutil,sys

def merge(source,destination):
    manifests=list(source.glob('*/tools/pyramid/*/runner.json'))
    expected={s+'-'+a for s in ('windows','linux','macos') for a in ('x86_64','aarch64')}
    actual={json.loads(p.read_text(encoding='utf-8'))['platform'] for p in manifests}
    if actual!=expected or len(manifests)!=len(expected):raise ValueError('Expected exactly six distinct platform artifacts')
    identities=set()
    for path in manifests:
        m=json.loads(path.read_text(encoding='utf-8'));bundle=path.parents[3]
        if hashlib.sha256((path.parent/m['executable']).read_bytes()).hexdigest()!=m['binary_sha256']:raise ValueError('Artifact binary hash mismatch')
        if hashlib.sha256((bundle/'tools/pyramid/uimage.zip').read_bytes()).hexdigest()!=m['templates_sha256']:raise ValueError('Artifact template hash mismatch')
        identities.add((m['templates_sha256'],m['core_baseline_sha256'],m['core_version'],m['upstream_commit']))
    if len(identities)!=1:raise ValueError('Native bundles disagree on templates or frozen core')
    for folder in sorted(source.iterdir()):
        if folder.is_dir():shutil.copytree(folder,destination,dirs_exist_ok=True)
    print('Merged six validated native platforms')

if __name__=='__main__':merge(Path(sys.argv[1]),Path(sys.argv[2]))
