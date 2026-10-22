"""Archive the ordinary source pack without transforming any source file."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
source = root / 'demo-addon/welcome'
destination = root / 'artifacts/welcome-demo.zip'
destination.parent.mkdir(exist_ok=True)
with ZipFile(destination, 'w', ZIP_DEFLATED) as archive:
    for path in sorted((source / 'behavior_pack').rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(source).as_posix())
print(destination)
