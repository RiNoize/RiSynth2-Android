from pathlib import Path
import hashlib, runpy

def blob(data):
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()

pending=[]
for module in sorted(Path('.ci').glob('changes[123].py')):
    for name,before,after,edits in runpy.run_path(str(module))['CHANGES']:
        path=Path(name)
        assert not path.is_absolute() and '..' not in path.parts, name
        data=path.read_bytes() if path.exists() else b''
        assert (blob(data) if path.exists() else None)==before, 'Changed baseline: '+name
        lines=data.decode().splitlines(keepends=True)
        for start,end,text in reversed(edits):
            lines[start:end]=text.splitlines(keepends=True)
        result=''.join(lines).encode()
        assert blob(result)==after, 'Unexpected result: '+name
        pending.append((path,result))
assert len(pending)==20
for path,data in pending:
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(data)
print('All 20 reviewed file hashes match the locally tested candidate.')
