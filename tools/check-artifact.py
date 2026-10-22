"""Confirm the distributable contains only our adapter and known open-source native dependencies."""
from pathlib import Path
from zipfile import ZipFile
from io import BytesIO
import hashlib
import json

root = Path(__file__).resolve().parents[1]
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
with ZipFile(apk) as package:
    libraries = sorted(n for n in package.namelist() if n.endswith('.so'))
    allowed = {'lib/arm64-v8a/'+name for name in (
        'libmcpy_bridge.so', 'libandroidx.graphics.path.so', 'libchaquopy_java.so',
        'libcrypto_chaquopy.so', 'libcrypto_python.so', 'libpython3.13.so', 'libpython3.so',
        'libsqlite3_chaquopy.so', 'libsqlite3_python.so', 'libssl_chaquopy.so', 'libssl_python.so')}
    allowed.update('assets/chaquopy/bootstrap-native/arm64-v8a/'+name+'.cpython-313-aarch64-linux-android.so'
                   for name in ('_bz2','_ctypes','_datetime','_lzma','_opcode','_random','_struct','binascii','math','mmap','zlib'))
    allowed.add('assets/chaquopy/bootstrap-native/arm64-v8a/java/chaquopy.so')
    assert set(libraries) <= allowed, libraries
    assert 'lib/arm64-v8a/libmcpy_bridge.so' in libraries
    assert not any(n.endswith('engine.apk') or n.endswith('vanilla.mcp') for n in package.namelist())
    with ZipFile(BytesIO(package.read('assets/game-adapter.apk'))) as adapter:
        assert not any('libminecraftpe' in n or 'vanilla.mcp' in n for n in adapter.namelist())
print(json.dumps({'apk': str(apk), 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'nativeLibraries': libraries, 'officialRuntimeBundled': False}, indent=2))
