"""Fail closed on credential/chat/player files and recognizable secrets.

Scan prospective Git files, every historical blob, and optional release assets.
Print locations and rule names only; never print a matched secret.
This is a deterministic pre-publication check, not a proof of absence of secrets.
"""
from pathlib import Path, PurePosixPath
import argparse, io, json, re, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[2]
PRIVATE_PARTS = {'.codex', '.claude', '.cursor', '.minecraft', '.vscode', '.idea',
                 'build', '.gradle', 'run', 'resourcepacks', 'saves', 'logs',
                 'crash-reports', 'screenshots', 'packmod-tools', 'sessions', 'conversations'}
PRIVATE_NAMES = {'launcher_accounts.json', 'launcher_profiles.json', 'accounts.json',
                 'usercache.json', 'servers.dat', 'options.txt', 'history.jsonl', 'agents.md'}
RULES = {
    'GitHub token': rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{35,})',
    'OpenAI-style key': rb'sk-(?:(?:proj|svcacct)-)?[A-Za-z0-9_-]{25,}',
    'AWS access key': rb'(?:AKIA|ASIA)[A-Z0-9]{16}',
    'Google API key': rb'AIza[A-Za-z0-9_-]{35}',
    'private key': rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    'credential literal': rb'''(?i)(?:["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password)["']?)\s*[:=]\s*["']?([A-Za-z0-9_./+=-]{12,})''',
    'bearer literal': rb'(?i)Bearer\s+([A-Za-z0-9_./+=-]{16,})',
    'local user path': rb'(?i)[A-Z]:[\\/]+Users[\\/]+(?!Public(?:[\\/]|$)|Default(?:[\\/]|$))[^\s"<>\\/]+',
}
PATTERNS = {name: re.compile(pattern) for name, pattern in RULES.items()}
# A deliberately fake input tests diagnostic redaction. No other file is exempt.
FAKE_CREDENTIALS = {b'secret-example', b'private-token'}
FIXTURE = 'tools/core/com/embers/packmod/PackConversionCheck.java'


def private_path(name):
    parts = PurePosixPath(name.replace('\\', '/')).parts
    leaf = parts[-1].lower() if parts else ''
    return (any(p.lower() in PRIVATE_PARTS for p in parts)
            or leaf in PRIVATE_NAMES or leaf.startswith(('.env', 'rollout', 'credentials', 'secrets'))
            or leaf.endswith(('.pem', '.key', '.pfx', '.p12', '.jks')))


def scan_bytes(label, data, findings, depth=0):
    # UTF-16 strings can be embedded in executables and launcher files.
    samples = [data]
    if b'\x00' in data:
        for offset in (0, 1):
            samples.append(data[offset:].decode('utf-16-le', errors='ignore').encode('utf-8'))
    for sample in samples:
        for name, pattern in PATTERNS.items():
            for match in pattern.finditer(sample):
                value = match.group(1) if match.lastindex else match.group()
                if name == 'local user path' and label.lower().endswith(('.exe', '.dll')):
                    # Report compiler build paths separately; no tokens/accounts are exempt.
                    findings['compiler_paths'].add(label)
                    continue
                if label.split(':', 1)[-1] == FIXTURE and value in FAKE_CREDENTIALS:
                    continue
                findings['blocked'].add((label, name))
    if depth < 3 and data.startswith(b'PK\x03\x04'):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for entry in archive.infolist():
                    if entry.is_dir():
                        continue
                    inner = label + '!' + entry.filename
                    if private_path(entry.filename):
                        findings['blocked'].add((inner, 'private filename'))
                    if entry.file_size > 32 * 1024 * 1024:
                        findings['blocked'].add((inner, 'archive entry exceeds scan budget'))
                    else:
                        scan_bytes(inner, archive.read(entry), findings, depth + 1)
        except zipfile.BadZipFile:
            findings['blocked'].add((label, 'unreadable archive'))


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--assets', nargs='*', type=Path, default=[])
    parser.add_argument('--report', type=Path)
    parser.add_argument('--history-ref', default='--all', help='History that will be published; use HEAD for a clean release branch.')
    args = parser.parse_args()
    findings = {'blocked': set(), 'compiler_paths': set()}
    names = sorted(set(p.decode('utf-8') for p in git('ls-files', '--cached', '--others', '--exclude-standard', '-z').split(b'\0') if p))
    for name in names:
        if private_path(name):
            findings['blocked'].add((name, 'private filename'))
        path = ROOT / name
        if path.is_file():
            scan_bytes(name, path.read_bytes(), findings)
    blobs = {}
    for line in git('rev-list', '--objects', args.history_ref).decode('utf-8').splitlines():
        sha, _, name = line.partition(' ')
        if name:
            blobs.setdefault(sha, name)
    for sha, name in blobs.items():
        if git('cat-file', '-t', sha).strip() != b'blob':
            continue
        if private_path(name):
            findings['blocked'].add(('history:' + name, 'private filename'))
        scan_bytes('history:' + name, git('cat-file', 'blob', sha), findings)
    for asset in args.assets:
        if not asset.is_file():
            findings['blocked'].add((asset.name, 'missing release asset'))
        else:
            scan_bytes(asset.name, asset.read_bytes(), findings)
    report = dict(prospective_source_files=len(names), historical_objects=len(blobs),
                  release_assets=len(args.assets), blocked_findings=sorted(findings['blocked']),
                  compiler_build_paths=sorted(findings['compiler_paths']),
                  passed=not findings['blocked'])
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
