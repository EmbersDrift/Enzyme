"""Optional private pack input and strict resource reload evidence for client audits."""
import hashlib,json,os
from pathlib import Path


def configure(path):
    if path is not None:
        source=Path(path).resolve()
        if not source.is_file():raise FileNotFoundError(source)
        os.environ['PACKMOD_AUDIT_REAL_PACK']=str(source)


def verify(run):
    source=os.environ.get('PACKMOD_AUDIT_REAL_PACK')
    if not source:return None
    record=json.loads((run/'real-pack-pass.json').read_text(encoding='utf8'))
    assert record['passed'] and record['resource_reload_passed']
    assert record['source_sha256']==hashlib.sha256(Path(source).read_bytes()).hexdigest()
    log=(run/'logs/latest.log').read_text(encoding='utf8',errors='replace')
    # Inspect the reload of the actual sample, excluding earlier synthetic failure tests.
    lines=log.splitlines();start=next(i for i,line in enumerate(lines) if 'Reloading ResourceManager:' in line and record['output'] in line)
    errors=[line for line in lines[start:] if any(marker in line for marker in (
        "Couldn't parse item model",'Failed to load model',"Couldn't load model",'Unable to load model',
        'Missing block model','Missing textures in model','Unable to resolve texture',
        'Caught error loading resourcepacks','Shader compilation failed','Failed to compile shader',
        "Couldn't compile",'Failed to create shader','Failed to link','Invalid shader',
        'Shader compilation log','STANDALONE AUDIT failed'))]
    assert not errors,'Real pack resource reload errors:\n'+'\n'.join(errors)
    return record
