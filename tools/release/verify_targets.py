"""Check every declared conversion target against Mojang's shipped version.json."""
from pathlib import Path
import hashlib,json,os,zipfile

ROOT=Path(__file__).resolve().parents[2]
CACHE=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/fabric-loom'

def verify():
    fabric=json.loads((ROOT/'compat/versions.json').read_text())
    root=dict(line.split('=',1) for line in (ROOT/'gradle.properties').read_text().splitlines() if '=' in line)
    declared=json.loads((ROOT/'src/client/resources/assets/packmod/conversion-target.json').read_text())
    fabric[root['minecraft_version']]=dict(format=declared['major'])
    neo=json.loads((ROOT/'neoforge/versions.json').read_text())
    records=[]
    for mc,spec in fabric.items():
        info=json.loads((CACHE/mc/'mojang_minecraft_info.json').read_text())
        client=CACHE/mc/'minecraft-client.jar'
        assert info['id']==mc and hashlib.sha1(client.read_bytes()).hexdigest()==info['downloads']['client']['sha1'],(mc,'official client hash')
        with zipfile.ZipFile(client) as z:version=json.loads(z.read('version.json'))
        actual=version['pack_version']
        major=actual.get('resource_major',actual.get('resource'));minor=actual.get('resource_minor',0)
        assert version['id']==mc and (major,minor)==(spec['format'],0),(mc,actual,spec)
        if mc in neo:assert neo[mc]['format']==major,(mc,'NeoForge target differs')
        records.append(dict(minecraft=mc,major=major,minor=minor,official_client_sha1=info['downloads']['client']['sha1'],loaders=['fabric']+(['neoforge'] if mc in neo else []),passed=True))
    assert set(neo)<=set(fabric)
    destination=ROOT/'build/conversion-targets.json';destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(dict(passed=True,versions=records),indent=2)+'\n')
    print('Official conversion targets verified: '+', '.join(r['minecraft']+'='+str(r['major'])+'.'+str(r['minor']) for r in records))
    return records

if __name__=='__main__':verify()
