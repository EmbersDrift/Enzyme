"""Verify executable architecture detection and fail closed on unknown headers."""
from pathlib import Path
import hashlib,importlib.util,json,struct,tempfile,unittest
spec=importlib.util.spec_from_file_location('package_support',Path(__file__).resolve().parents[2]/'pyramid-bridge/package_support.py')
support=importlib.util.module_from_spec(spec);spec.loader.exec_module(support)
spec=importlib.util.spec_from_file_location('merge_bundles',Path(__file__).with_name('merge_bundles.py'))
bundles=importlib.util.module_from_spec(spec);spec.loader.exec_module(bundles)

class PackagingChecks(unittest.TestCase):
    def test_native_architectures(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'binary'
            for arch,pe,elf,mach in [('x86_64',0x8664,62,0x1000007),('aarch64',0xaa64,183,0x100000c)]:
                data=bytearray(256);data[:2]=b'MZ';struct.pack_into('<I',data,60,128);data[128:132]=b'PE\0\0';struct.pack_into('<H',data,132,pe);p.write_bytes(data)
                self.assertEqual(support.native_platform(p),'windows-'+arch)
                data=bytearray(64);data[:4]=b'\x7fELF';data[5]=1;struct.pack_into('<H',data,18,elf);p.write_bytes(data)
                self.assertEqual(support.native_platform(p),'linux-'+arch)
                data=bytearray(64);data[:4]=b'\xcf\xfa\xed\xfe';struct.pack_into('<I',data,4,mach);p.write_bytes(data)
                self.assertEqual(support.native_platform(p),'macos-'+arch)
    def test_unknown_binary_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'fake';p.write_bytes(b'not a native executable')
            with self.assertRaises(ValueError):support.native_platform(p)
    def test_merge_rejects_tampering_before_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            source=Path(temp)/'artifacts';destination=Path(temp)/'merged'
            manifests=[]
            for system in ('windows','linux','macos'):
                for arch in ('x86_64','aarch64'):
                    platform=system+'-'+arch
                    root=source/platform/'tools/pyramid';runner=root/platform;runner.mkdir(parents=True)
                    executable='embers-pyramid'+('.exe' if system=='windows' else '')
                    (runner/executable).write_bytes(b'binary')
                    (root/'uimage.zip').write_bytes(b'templates')
                    manifest=runner/'runner.json'
                    manifest.write_text(json.dumps(dict(platform=platform,executable=executable,
                        binary_sha256=hashlib.sha256(b'binary').hexdigest(),templates_sha256=hashlib.sha256(b'templates').hexdigest(),
                        core_baseline_sha256='same',core_version='0.2.2',upstream_commit='same')),encoding='utf-8')
                    manifests.append(manifest)
            tampered=manifests[-1].parent/'embers-pyramid';tampered.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError,'binary hash'):bundles.merge(source,destination)
            self.assertFalse(destination.exists())
            tampered.write_bytes(b'binary')
            incompatible=json.loads(manifests[-1].read_text());incompatible['core_version']='other'
            manifests[-1].write_text(json.dumps(incompatible))
            with self.assertRaisesRegex(ValueError,'disagree'):bundles.merge(source,destination)
            self.assertFalse(destination.exists())
            incompatible['core_version']='0.2.2';manifests[-1].write_text(json.dumps(incompatible))
            bundles.merge(source,destination)
            self.assertEqual(len(list(destination.glob('tools/pyramid/*/runner.json'))),6)

if __name__=='__main__':unittest.main()
