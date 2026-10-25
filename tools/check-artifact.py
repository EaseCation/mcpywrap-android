"""Confirm the distributable contains only our adapter and known open-source native dependencies."""
from pathlib import Path
from zipfile import ZipFile
from io import BytesIO
import hashlib
import json
import os
import subprocess
from email.parser import BytesParser

root = Path(__file__).resolve().parents[1]
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
expected_package = 'net.easecation.mcpywrap'
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home()/'Library/Android/sdk')
aapt = sdk/'build-tools/35.0.0/aapt2'
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
assert badging.startswith("package: name='"+expected_package+"'"), 'Incorrect installable application ID'
assert "launchable-activity: name='"+expected_package+".MainActivity'" in badging, 'Incorrect launcher activity'
manifest = subprocess.check_output([str(aapt), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
for identity in (expected_package+'.GamePipLauncherActivity', expected_package+'.provider', expected_package+'.game', expected_package+'.confirmation'):
    assert '"'+identity+'"' in manifest, 'Missing component identity: '+identity
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
    expected_core = next(line.split('=',1)[1].strip() for line in (root/'gradle.properties').read_text().splitlines() if line.startswith('mcpyCoreVersion='))
    requirement_libraries = []
    embedded_core = None
    for layer in ('assets/chaquopy/requirements-common.imy', 'assets/chaquopy/requirements-arm64-v8a.imy'):
        with ZipFile(BytesIO(package.read(layer))) as requirements:
            for name in requirements.namelist():
                assert not any(part in name for part in ('libminecraftpe', 'vanilla.mcp', 'engine.apk')), name
                if name.endswith('.so') or '.so.' in Path(name).name:
                    assert name == 'rpds/rpds.cpython-313-aarch64-linux-android.so', name
                    requirement_libraries.append(name)
                if name.startswith('mcpywrap_core-') and name.endswith('.dist-info/METADATA'):
                    metadata = BytesParser().parsebytes(requirements.read(name))
                    assert metadata['Version'] == expected_core
                    embedded_core = metadata['Version']
    assert embedded_core == expected_core, 'Missing pinned shared core'
    assert requirement_libraries == ['rpds/rpds.cpython-313-aarch64-linux-android.so'], requirement_libraries
    with ZipFile(BytesIO(package.read('assets/game-adapter.apk'))) as adapter:
        assert not any('libminecraftpe' in n or 'vanilla.mcp' in n for n in adapter.namelist())
print(json.dumps({'apk': str(apk), 'packageId': expected_package, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'nativeLibraries': libraries, 'pythonNativeLibraries': requirement_libraries, 'coreVersion': embedded_core, 'officialRuntimeBundled': False}, indent=2))
