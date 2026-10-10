"""Launch the complete isolated mouse audit using the target version's actual Java runtime."""
from pathlib import Path
import argparse,os,subprocess,time
from generate import ROOT,VERSIONS,generate
from toolchains import installation_paths

parser=argparse.ArgumentParser();parser.add_argument('mc',choices=VERSIONS);args=parser.parse_args()
mc=args.mc
run=ROOT/'build/compat-runs'/mc/('run-'+str(time.time_ns()));run.mkdir(parents=True,exist_ok=True)
marker=run/'standalone-pass.txt';marker.unlink(missing_ok=True)
options=run/'options.txt'
if not options.exists():options.write_text('onboardAccessibility:false\n')
java_paths=installation_paths()
env=os.environ.copy();env.setdefault('GRADLE_USER_HOME',str(Path.home()/'.gradle'))
try:
    generate(mc,audit=True)
    command=[str(ROOT/'gradlew.bat'),'-p','compat','-Pmc='+mc,'-PauditRunDir='+str(run),'-Porg.gradle.java.installations.paths='+java_paths,'runClient','--console=plain']
    result=subprocess.run(command,cwd=ROOT,env=env)
    if result.returncode:raise SystemExit(result.returncode)
    if not marker.exists():raise SystemExit('Game audit failed: '+str(run/'logs/latest.log'))
    (run.parent/'last-audit.txt').write_text(str(run),encoding='utf-8')
    print(mc+': '+marker.read_text())
finally:
    generate(mc,audit=False)
