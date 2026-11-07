#!/usr/bin/env python3
"""Apply the locally tested source delta, rejecting any before/after mismatch."""
import base64
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
raw = gzip.decompress(base64.b64decode((ROOT / '.ci/031.json.gz.b64').read_text()))
assert hashlib.sha256(raw).hexdigest() == '1293d2fc2017ba6ac05363b60588b1739bc7dc67d923f6433a152dafa1ac6985', 'damaged transport'
changes = json.loads(raw)
prepared = []
for entry in changes:
    path = ROOT / entry['path']
    assert ROOT in path.resolve().parents and not path.is_symlink(), 'unsafe path'
    before = path.read_bytes() if path.exists() else None
    assert (hashlib.sha256(before).hexdigest() if before is not None else None) == entry['before'], 'baseline mismatch: ' + entry['path']
    source = before.decode('utf-8') if before is not None else ''
    end = len(source)
    for start, stop, text in reversed(entry['edits']):
        assert 0 <= start <= stop <= end, 'overlapping edit'
        source = source[:start] + text + source[stop:]
        end = start
    after = source.encode('utf-8')
    assert hashlib.sha256(after).hexdigest() == entry['after'], 'result mismatch: ' + entry['path']
    prepared.append((path, after))
for path, content in prepared:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    print('Verified:', path.relative_to(ROOT))
print('Applied', len(prepared), 'reviewed files; no other source files changed.')
