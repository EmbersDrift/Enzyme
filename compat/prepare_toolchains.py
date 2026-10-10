"""Fetch verified Java 17/21 x64 JDKs into a cache outside Gradle clean."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib, json, os, urllib.request, zipfile

CACHE = Path(os.environ.get('LOCALAPPDATA', str(Path.home() / '.cache'))) / 'Enzyme/jdks'
PACKAGES = {17: '79a4ebbc-1a6f-4f10-afd4-3e79e68b331b', 21: 'd02613cd-7cae-488b-ab30-704ba8f0a627'}


def prepare(major):
    metadata = json.loads(urllib.request.urlopen('https://api.azul.com/metadata/v1/zulu/packages/' + PACKAGES[major]).read())
    assert 'win_x64' in metadata['name'] and '-jdk' + str(major) + '.' in metadata['name']
    directory = CACHE / str(major)
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / metadata['name']
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != metadata['sha256_hash']:
        print('Downloading verified Java ' + str(major) + ' x64 JDK', flush=True)
        pending = archive.with_suffix('.zip.tmp')
        urllib.request.urlretrieve(metadata['download_url'], pending)
        assert pending.stat().st_size == metadata['size']
        assert hashlib.sha256(pending.read_bytes()).hexdigest() == metadata['sha256_hash']
        pending.replace(archive)
    with zipfile.ZipFile(archive) as bundle:
        for name in bundle.namelist():
            assert (directory / name).resolve().is_relative_to(directory.resolve())
        bundle.extractall(directory)
    java = next(directory.glob('*/bin/java.exe'))
    print('Java ' + str(major) + ' cached outside build; published SHA256 verified', flush=True)
    return java


if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(prepare, PACKAGES))
