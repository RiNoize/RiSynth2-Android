#!/usr/bin/env python3
"""Apply two reviewed deltas; abort on any unexpected before/after byte."""
import base64, hashlib, json, lzma, pathlib, subprocess, sys
root = pathlib.Path(__file__).resolve().parents[1]
packed = ''.join((root / '.ci' / f'032payload.{i}').read_text().strip() for i in range(3))
raw = lzma.decompress(base64.b64decode(packed, validate=True))
assert hashlib.sha256(raw).hexdigest() == '15af60194a6d6f8ba7f0ae0f473bbcf13c9cbc4f2a88aad8711c65724ac2dea5', 'Transport mismatch'
obj = json.loads(raw)
subprocess.run(['git', 'merge-base', '--is-ancestor', obj['base'], 'HEAD'], cwd=root, check=True)
for group in obj['groups']:
    for f in group['files']:
        p = pathlib.PurePosixPath(f['path'])
        assert not p.is_absolute() and '..' not in p.parts
        assert f['path'].startswith(('EngineCore/', 'android/')) or f['path'] in ('AGENTS.md', 'ANDROID.md')
        target = root / p
        before = hashlib.sha256(target.read_bytes()).hexdigest() if target.exists() else None
        assert before == f['before'], f"Unexpected source: {p}"
    patch = group['patch'].encode('utf-8')
    for args in (['--check'], []):
        subprocess.run(['git', 'apply', '--unidiff-zero', *args, '-'], input=patch, cwd=root, check=True)
    for f in group['files']:
        assert hashlib.sha256((root / f['path']).read_bytes()).hexdigest() == f['after'], f"Output mismatch: {f['path']}"
    if '--commit' in sys.argv:
        subprocess.run(['git', 'add', '--', *[f['path'] for f in group['files']]], cwd=root, check=True)
        subprocess.run(['git', 'commit', '-m', group['message']], cwd=root, check=True)
    print('Verified:', group['message'].splitlines()[0])
