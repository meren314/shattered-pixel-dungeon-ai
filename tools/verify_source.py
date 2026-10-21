"""Check the files Git would publish, not ignored local build outputs."""
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
files = [name for name in files if name]
errors = []
for name in files:
    path = ROOT / name
    if any(part in {'.gradle', 'build', '__pycache__', 'save-backups', 'playruns', 'request-receipts'} for part in path.relative_to(ROOT).parts):
        errors.append(f'Runtime directory: {name}')
    if path.suffix in {'.log', '.jsonl', '.dat', '.pyc', '.class', '.jks', '.keystore'}:
        errors.append(f'Private/generated file: {name}')
    if path.stat().st_size >= 90 * 1024 * 1024:
        errors.append(f'Oversized file: {name}')
    if path.suffix not in {'.java', '.py', '.ps1', '.cmd', '.md', '.gradle', '.properties', '.yml'}:
        continue
    text = path.read_text(encoding='utf-8-sig')
    if re.search(r'[A-Z]:\\Users\\|[A-Z]:\\Desktop\\', text):
        errors.append(f'Personal absolute path: {name}')
    if re.search(r'-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----|(?:sk-ant-|ghp_|github_pat_)[A-Za-z0-9_]{20,}', text):
        errors.append(f'Credential pattern: {name}')

for name in ['README.md', 'SECURITY.md', 'NOTICE.md', 'CONTRIBUTING.md',
             'ai-probe/player-agent/AGENTS.md', 'ai-probe/player-agent/AI能力报告.md']:
    path = ROOT / name
    for link in re.findall(r'\]\(([^)]+)\)', path.read_text(encoding='utf-8-sig')):
        if '://' not in link and not link.startswith('#') and not (path.parent / link.split('#')[0]).exists():
            errors.append(f'Broken local link: {name}: {link}')
print(json.dumps({'tracked_files': len(files), 'errors': errors}, ensure_ascii=False, indent=2))
raise SystemExit(bool(errors))
