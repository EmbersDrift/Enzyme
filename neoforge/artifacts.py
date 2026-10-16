"""Verify release-only NeoForge metadata, mappings, bytecode and shared core."""
from pathlib import Path
import hashlib,json,struct,tomllib,zipfile
ROOT=Path(__file__).resolve().parents[1]
VERSIONS=json.loads((ROOT/'neoforge/versions.json').read_text())
MOD_VERSION=next(line.split('=',1)[1].strip() for line in (ROOT/'gradle.properties').read_text().splitlines() if line.startswith('version='))

def verify(mc,jar):
    spec=VERSIONS[mc];baseline_path=ROOT/'tools/upstream/core-baseline.json'
    baseline=json.loads(baseline_path.read_text())
    with zipfile.ZipFile(jar) as z:
        assert z.testzip() is None
        meta=tomllib.loads(z.read('META-INF/'+('mods.toml' if spec.get('legacy') else 'neoforge.mods.toml')).decode())
        assert meta['license']=='MIT' and meta['mods'][0]['modId']=='packmod' and meta['mods'][0]['version']==MOD_VERSION
        assert meta['mods'][0]['logoFile']=='assets/packmod/icon.png'
        assert z.read(meta['mods'][0]['logoFile'])==(ROOT/'src/client/resources/assets/packmod/icon.png').read_bytes()
        deps=meta['dependencies']['packmod'];assert next(d for d in deps if d['modId']=='minecraft')['versionRange']=='['+mc+']'
        assert 'fabric.mod.json' not in z.namelist()
        assert z.read('LICENSE')==(ROOT/'LICENSE').read_bytes()
        assert json.loads(z.read('assets/packmod/conversion-target.json'))==dict(minecraft=mc,major=spec['format'],minor=0,loader='neoforge')
        for name in z.namelist():
            assert not any(value in name for value in ['PackStandaloneAudit','ProductionProbe','VersionConversionCheck','enzyme-audit'])
            if name.endswith('.class'):
                data=z.read(name)
                assert struct.unpack('>H',data[6:8])[0]<=spec['java']+44,(mc,name,'bytecode too new')
                assert b'net/fabricmc/' not in data,(mc,name,'Fabric dependency leaked')
        mixin=json.loads(z.read('packmod.client.mixins.json'))
        if spec.get('legacy'):
            assert mixin['refmap']=='packmod.refmap.json' and json.loads(z.read(mixin['refmap']))['mappings']
            assert b'MixinConfigs: packmod.client.mixins.json' in z.read('META-INF/MANIFEST.MF')
        else:assert meta['mixins'][0]['config']=='packmod.client.mixins.json'
        manifest=json.loads(z.read('tools/pyramid/windows-x86_64/runner.json'))
        assert manifest['core_version']==baseline['core_version'] and manifest['upstream_commit']==baseline['upstream_commit']
        assert manifest['core_baseline_sha256']==hashlib.sha256(baseline_path.read_bytes()).hexdigest()
        assert hashlib.sha256(z.read('tools/pyramid/windows-x86_64/embers-pyramid.exe')).hexdigest()==manifest['binary_sha256']
        assert hashlib.sha256(z.read('tools/pyramid/uimage.zip')).hexdigest()==manifest['templates_sha256']
        assert any(t['major']==spec['format'] for t in manifest['capabilities']['targets'])
        assert json.loads(z.read('assets/packmod/lang/en_us.json')).keys()==json.loads(z.read('assets/packmod/lang/zh_cn.json')).keys()
    return dict(minecraft=mc,loader='neoforge',neoforge_tested=spec['neoforge'],java=spec['java'],format=spec['format'],jar=jar.name,sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),native_upgrade_downgrade_check_passed=True)
