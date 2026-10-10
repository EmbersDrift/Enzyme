"""Resolve the two actual Java runtimes used by compilation and game audits."""
from functools import lru_cache
from pathlib import Path
import os, re, shutil, subprocess
from generate import ROOT


@lru_cache(maxsize=None)
def java_home(major):
    candidates = []
    for key in ('PACKMOD_JAVA' + str(major) + '_HOME', 'JAVA_HOME_' + str(major) + '_X64', 'JAVA_HOME'):
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]))
    directory = ROOT / 'build/compat-jdks' / ('17-x64' if major == 17 else '21')
    candidates.extend(p.parent.parent for p in directory.glob('*/bin/java.exe'))
    executable = shutil.which('java')
    if executable:
        candidates.append(Path(executable).resolve().parent.parent)
    for home in candidates:
        java = home / 'bin/java.exe'
        if not java.is_file():
            continue
        result = subprocess.run([str(java), '-XshowSettings:properties', '-version'], capture_output=True, text=True, errors='replace')
        if result.returncode == 0 and re.search(r'java.specification.version\s*=\s*' + str(major) + r'\s', result.stderr) and re.search(r'os.arch\s*=\s*(amd64|x86_64)\s', result.stderr):
            return home.resolve()
    raise RuntimeError('Set PACKMOD_JAVA' + str(major) + '_HOME to a Java ' + str(major) + ' x64 JDK directory.')


def installation_paths():
    return ','.join(str(java_home(major)) for major in (17, 21))
