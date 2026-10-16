"""Materialize official launcher asset indexes and validate their content hashes.

Loom prefixes cached index filenames with the game version; production launchers
request the original Mojang assetIndex.id filename instead.
"""
from pathlib import Path
import concurrent.futures,hashlib,json,os,tempfile,urllib.request


def ensure(cache,info):
    root=Path(cache)/'assets';index=info['assetIndex'];directory=root/'indexes';directory.mkdir(parents=True,exist_ok=True)
    path=directory/(index['id']+'.json')
    def valid_index(p):return p.is_file() and hashlib.sha1(p.read_bytes()).hexdigest()==index['sha1']
    if not valid_index(path):
        cached=next((p for p in directory.glob('*-'+index['id']+'.json') if valid_index(p)),None)
        payload=cached.read_bytes() if cached else urllib.request.urlopen(index['url'],timeout=60).read()
        assert hashlib.sha1(payload).hexdigest()==index['sha1'],'Asset index hash mismatch'
        with tempfile.NamedTemporaryFile(dir=directory,delete=False) as pending:pending.write(payload);temporary=Path(pending.name)
        try:os.replace(temporary,path)
        finally:temporary.unlink(missing_ok=True)
    objects=json.loads(path.read_text())['objects'];unique={v['hash']:v for v in objects.values()}
    def verify(item):
        digest,entry=item;target=root/'objects'/digest[:2]/digest
        if target.is_file() and target.stat().st_size==entry['size'] and hashlib.sha1(target.read_bytes()).hexdigest()==digest:return
        target.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=target.parent,delete=False) as pending:temporary=Path(pending.name)
        try:
            urllib.request.urlretrieve('https://resources.download.minecraft.net/'+digest[:2]+'/'+digest,temporary)
            assert temporary.stat().st_size==entry['size'] and hashlib.sha1(temporary.read_bytes()).hexdigest()==digest,'Asset content hash mismatch'
            os.replace(temporary,target)
        finally:temporary.unlink(missing_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(verify,unique.items()))
    print('Official game assets verified: '+info['id']+' ('+str(len(unique))+' objects)',flush=True)
    return dict(index=index['id'],sha1=index['sha1'],objects=len(unique),verified=True)
