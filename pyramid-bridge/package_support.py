"""Packaging checks outside the frozen conversion core."""
from pathlib import Path
import argparse, hashlib, json, os, platform, struct, subprocess

def native_platform(binary):
    data=binary.read_bytes()
    if data[:2]==b'MZ':
        offset=struct.unpack_from('<I',data,60)[0]
        if data[offset:offset+4]!=b'PE\0\0':raise ValueError('Invalid PE executable')
        return 'windows-'+{0x8664:'x86_64',0xaa64:'aarch64'}[struct.unpack_from('<H',data,offset+4)[0]]
    if data[:4]==b'\x7fELF':return 'linux-'+{62:'x86_64',183:'aarch64'}[struct.unpack_from('<H' if data[5]==1 else '>H',data,18)[0]]
    if data[:4] in (b'\xcf\xfa\xed\xfe',b'\xfe\xed\xfa\xcf'):return 'macos-'+{0x1000007:'x86_64',0x100000c:'aarch64'}[struct.unpack_from('<I' if data[:4]==b'\xcf\xfa\xed\xfe' else '>I',data,4)[0]]
    raise ValueError('Unknown native executable format')

def prepare(root):
    parser=argparse.ArgumentParser()
    parser.add_argument('--platform',choices=[s+'-'+a for s in ('windows','linux','macos') for a in ('x86_64','aarch64')])
    parser.add_argument('--binary',type=Path);parser.add_argument('--resources-dir',type=Path)
    parser.add_argument('--capabilities',type=Path,help='Capability JSON for another host')
    args=parser.parse_args()
    system={'Windows':'windows','Linux':'linux','Darwin':'macos'}.get(platform.system())
    arch={'AMD64':'x86_64','x86_64':'x86_64','arm64':'aarch64','aarch64':'aarch64'}.get(platform.machine())
    host=f'{system}-{arch}';platform_id=args.platform or host
    target=Path(os.environ.get('CARGO_TARGET_DIR',root/'target'))
    executable='embers-pyramid'+('.exe' if platform_id.startswith('windows-') else '')
    binary=(args.binary or target/'release'/executable).resolve()
    if not binary.is_file():raise SystemExit(f'Build the {platform_id} runner first: {binary}')
    if native_platform(binary)!=platform_id:raise SystemExit('Executable OS/CPU does not match selected platform')
    baseline_path=root.parent/'tools/upstream/core-baseline.json';baseline=json.loads(baseline_path.read_text(encoding='utf-8'))
    if args.capabilities:capabilities=json.loads(args.capabilities.read_text(encoding='utf-8'))
    else:
        try:capabilities=json.loads(subprocess.check_output([str(binary),'--capabilities'],timeout=10))
        except OSError:raise SystemExit('Cross-host packaging requires --capabilities')
    if capabilities['schema_version']!=baseline['protocol_version'] or capabilities['core_version']!=baseline['core_version']:raise SystemExit('Runner version/protocol differs from frozen core')
    if capabilities['os']+'-'+capabilities['arch']!=platform_id:raise SystemExit('Capability platform does not match executable')
    resources=(args.resources_dir or root.parent/'src/client/resources').resolve()
    manifest={'schema_version':1,'platform':platform_id,'executable':executable,'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'core_baseline_sha256':hashlib.sha256(baseline_path.read_bytes()).hexdigest(),'core_version':baseline['core_version'],'upstream_commit':baseline['upstream_commit'],'capabilities':capabilities}
    return resources,binary,resources/'tools/pyramid'/platform_id/executable,manifest

def finish(destination,bundle,manifest):
    manifest['templates_sha256']=hashlib.sha256((bundle/'uimage.zip').read_bytes()).hexdigest()
    (destination.parent/'runner.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
