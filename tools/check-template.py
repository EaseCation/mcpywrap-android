"""Verify the APK embeds every template file unchanged, including __init__.py."""
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
source = root / 'demo-addon/welcome'
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
with ZipFile(apk) as package:
    with ZipFile(BytesIO(package.read('welcome-template.zip'))) as template:
        files = [p for p in (source / 'behavior_pack').rglob('*') if p.is_file()]
        expected = {p.relative_to(source).as_posix() for p in files}
        assert {n for n in template.namelist() if not n.endswith('/')} == expected
        for path in files:
            assert template.read(path.relative_to(source).as_posix()) == path.read_bytes(), path
print(f'All {len(files)} ordinary Mod files preserved byte-for-byte in the APK.')
