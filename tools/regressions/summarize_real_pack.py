"""Independently verify actual sample predicates and bind all results to shipped JARs."""
from pathlib import Path
import argparse,hashlib,itertools,json,os,sys,zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/game'))
import real_pack


def resource(value):return value if ':' in value else 'minecraft:'+value


def evaluate(node,values):
    kind=node['type'].split(':')[-1]
    if kind=='model':return resource(node['model'])
    if kind=='special':return resource(node['base'])
    key=node['property'].split(':',1)[-1]
    if key=='using_item':key='blocking'
    value=values.get(key,0)
    if kind=='condition':return evaluate(node['on_true' if value>0 else 'on_false'],values)
    assert kind=='range_dispatch',kind
    if key=='custom_model_data':assert node.get('index',0)==0
    if key=='damage':assert node.get('normalize',True)
    selected=node['fallback']
    for entry in node['entries']:
        if value>=entry['threshold']:selected=entry['model']
    return evaluate(selected,values)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('source',type=Path);args=parser.parse_args()
    source=args.source.resolve();digest=hashlib.sha256(source.read_bytes()).hexdigest();real_pack.configure(source)
    version=next(l.split('=',1)[1] for l in (ROOT/'gradle.properties').read_text().splitlines() if l.startswith('version='))
    matrices=[json.loads((ROOT/'build/real-pack-audit'/('matrix-'+loader+'.json')).read_text()) for loader in ('fabric','neoforge')]
    assert all(m['version']==version and m['source_sha256']==digest for m in matrices)
    records=[r for m in matrices for r in m['versions']]
    fabric=json.loads((ROOT/'compat/versions.json').read_text());neo=json.loads((ROOT/'neoforge/versions.json').read_text())
    assert len(records)==13 and {(r['loader'],r['minecraft']) for r in records}=={('fabric',v) for v in ['26.2',*fabric]}|{('neoforge',v) for v in neo}
    total_predicates=0;total_custom=0
    with zipfile.ZipFile(source) as original:
        custom={n:original.read(n) for n in original.namelist() if n.startswith('assets/') and len(n.split('/'))>2 and n.split('/')[1]!='minecraft' and not n.endswith('/')}
        models={n:json.loads(original.read(n)) for n in original.namelist() if '/models/item/' in n and n.endswith('.json')}
        for record in records:
            loader,mc=record['loader'],record['minecraft'];project='neoforge' if loader=='neoforge' else ('compat' if mc!='26.2' else None)
            directory=ROOT/('build/'+project+'/'+mc if project else 'build')
            jar=directory/'libs'/('enzyme-'+('neoforge-' if loader=='neoforge' else '')+mc+'-'+version+'.jar')
            assert hashlib.sha256(jar.read_bytes()).hexdigest()==record['jar_sha256']
            proof=json.loads((ROOT/'build'/('neoforge-production-runs' if loader=='neoforge' else 'compat-production-runs')/mc/'verification.json').read_text())
            assert proof['jar_sha256']==record['jar_sha256'] and proof['official_assets']['verified']
            run=Path(proof['production_audit_directory']);real_pack.verify(run)
            assert "Can't open the resource index file" not in (run/'logs/latest.log').read_text('utf8',errors='replace')
            latest={}
            for path in sorted((directory/'user-pack-check').glob('run-*/verification.json'),key=lambda p:p.stat().st_mtime_ns):
                check=json.loads(path.read_text('utf8'))
                if check.get('preset') in {'conservative','defaults','complete'}:latest[check['preset']]=check
            assert set(latest)=={'conservative','defaults','complete'}
            predicates=0;custom_count=0
            for preset,check in latest.items():
                with zipfile.ZipFile(check['installed_output']) as output:
                    for name,payload in custom.items():
                        assert output.read(name)==payload,(loader,mc,preset,'Custom namespace changed',name)
                        custom_count+=1
                    if record['format']>=46:
                        for name,model in models.items():
                            if 'overrides' not in model:continue
                            base=resource(name.split('/')[1]+':'+name.split('/models/',1)[1][:-5])
                            definition=json.loads(output.read(name.replace('/models/item/','/items/')))['model']
                            keys=sorted({k for r in model['overrides'] for k in r['predicate']})
                            values={k:{0.0,1.0} for k in keys}
                            for rule in model['overrides']:
                                for key,value in rule['predicate'].items():
                                    if key not in {'damaged','blocking','pulling','cast'}:values[key].update([value,max(0,value-.01),value+1])
                            for case in itertools.product(*(sorted(values[k]) for k in keys)):
                                properties=dict(zip(keys,case));expected=base
                                for rule in model['overrides']:
                                    if all(properties[k]>=v for k,v in rule['predicate'].items()):expected=resource(rule['model'])
                                assert evaluate(definition,properties)==expected,(loader,mc,preset,name,properties)
                                predicates+=1
                            retained=json.loads(output.read(name))
                            for field in ('display','textures'):
                                if field in model:assert retained[field]==model[field],(loader,mc,name,field)
                            assert retained.get('parent') not in ('builtin/entity','minecraft:builtin/entity')
            total_predicates+=predicates;total_custom+=custom_count
            record['independent_predicate_cases']=predicates;record['custom_namespace_files_verified']=custom_count
            print('FINAL SAMPLE PASS',loader,mc,'predicate cases',predicates,'custom assets',custom_count,flush=True)
    assert digest==hashlib.sha256(source.read_bytes()).hexdigest()
    report=dict(version=version,passed=True,source_sha256=digest,source_unchanged=True,builds=len(records),conversions=len(records)*3,resource_reloads=len(records),predicate_cases=total_predicates,custom_asset_checks=total_custom,versions=records)
    path=ROOT/'build/real-pack-audit/summary.json';path.write_text(json.dumps(report,indent=2)+'\n',encoding='utf8');print('REAL PACK SUMMARY PASS',path)


if __name__=='__main__':main()
