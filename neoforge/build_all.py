"""Build and validate each NeoForge adapter without touching conversion sources."""
import argparse,json,shutil
from generate import ROOT,VERSIONS,MOD_VERSION,generate
from production_audit import gradle,audit

from artifacts import verify

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--versions',nargs='+',choices=VERSIONS,default=list(VERSIONS))
    parser.add_argument('--production-audit',action='store_true');parser.add_argument('--reuse-production-audit',action='store_true')
    parser.add_argument('--collect-only',action='store_true',help='Validate existing release JARs and matching audit evidence without rebuilding.')
    args=parser.parse_args()
    records=[];delivery=ROOT/'build/neoforge-delivery';delivery.mkdir(exist_ok=True)
    for mc in args.versions:
        if not args.collect_only:generate(mc);gradle(mc,'clean','build')
        jar=ROOT/'build/neoforge'/mc/'libs'/('enzyme-neoforge-'+mc+'-'+MOD_VERSION+'.jar')
        record=verify(mc,jar)
        if args.production_audit:audit(mc)
        if args.production_audit or args.reuse_production_audit:
            proof=json.loads((ROOT/'build/neoforge-production-runs'/mc/'verification.json').read_text())
            assert proof['jar_sha256']==record['sha256'] and proof['production_jar_audit_passed']
            record.update(proof)
        shutil.copyfile(jar,delivery/jar.name);records.append(record)
    (delivery/'verification.json').write_text(json.dumps(dict(mod_version=MOD_VERSION,loader='neoforge',versions=records),indent=2)+'\n')
    print('Checked NeoForge delivery: '+str(delivery))
