"""Package a native runner, templates and dependency notices into the mod.

Run from any directory after cargo build --release --manifest-path .../Cargo.toml.
Requires Python 3.11+ and the Cargo registry sources used for that build.
"""
from pathlib import Path
import os
import shutil
import tomllib
import zipfile
import package_support

root = Path(__file__).resolve().parent
resources, binary, destination, manifest = package_support.prepare(root)
bundle = resources / "tools/pyramid"
destination.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(binary, destination)
with zipfile.ZipFile(bundle / "uimage.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((root / "UImage").rglob("*")):
        if path.is_file():
            info = zipfile.ZipInfo(path.relative_to(root / 'UImage').as_posix(), (2020,1,1,0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())

licenses = resources / "META-INF/licenses"
licenses.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root / "LICENSE-2-Pyramid", licenses / "2-Pyramid-MIT.txt")
registry = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "registry/src"
records = []
for package in tomllib.loads((root / "Cargo.lock").read_text(encoding="utf-8"))["package"]:
    if "source" not in package:
        continue
    name = f"{package['name']}-{package['version']}"
    folders = list(registry.glob(f"*/{name}"))
    if not folders:
        # Cargo.lock also includes crates for other targets, not fetched by
        # this platform's release build.
        continue
    folder = folders[0]
    metadata = tomllib.loads((folder / "Cargo.toml").read_text(encoding="utf-8"))["package"]
    notice_dir = licenses / "pyramid-dependencies" / name
    notice_dir.mkdir(parents=True, exist_ok=True)
    records.append(f"{name}: {metadata.get('license', 'see license file')}\n"
                   f"{metadata.get('repository', metadata.get('homepage', 'https://crates.io/crates/' + package['name']))}\n")
    files = [p for p in folder.rglob("*") if p.is_file() and p.name.lower().startswith(
        ("license", "copying", "copyright", "unlicense"))]
    for path in files:
        output = notice_dir / path.relative_to(folder)
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, output)
    if not files:
        # Some published subcrates omit the standalone license in their tarball.
        # Preserve their published attribution and declaration alongside MIT text.
        for path in [folder / "Cargo.toml", folder / "README.md", folder / "src/lib.rs"]:
            if path.is_file():
                shutil.copyfile(path, notice_dir / path.name)
        mit = next(registry.glob("*/pulp-*/LICENSE"))
        text = mit.read_text(encoding="utf-8")
        # Keep the standard license terms; attribution is in the sources above.
        terms = text[text.index("Permission is hereby granted"):]
        (notice_dir / "MIT-TERMS.txt").write_text(terms, encoding="utf-8")
(licenses / "pyramid-dependencies.txt").write_text(
    "2-Pyramid native runner dependency notices (Cargo.lock pinned versions).\n\n" + "\n".join(records),
    encoding="utf-8")
package_support.finish(destination, bundle, manifest)
print(f"Packaged {manifest['platform']}, core {manifest['core_version']}; SHA256 {manifest['binary_sha256']}")
