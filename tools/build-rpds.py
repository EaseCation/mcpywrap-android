#!/usr/bin/env python3
"""Build the upstream rpds extension for the embedded Python, without replacing its semantics."""
import base64
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT/'core/engineering/build/rpds'
OUTPUT = ROOT/'core/engineering/build/wheels/rpds_py-0.27.1-1-cp313-cp313-android_30_arm64_v8a.whl'
SOURCE_URL = 'https://files.pythonhosted.org/packages/e9/dd/2c0cbe774744272b0ae725f44032c77bdcab6e8bcf544bffa3b6e70c8dba/rpds_py-0.27.1.tar.gz'
SOURCE_SHA = '26a1c73171d10b7acccbded82bf6a586ab8203601e565badc74bbbf8bc5a10f8'
PYTHON_URL = 'https://repo.maven.apache.org/maven2/com/chaquo/python/target/3.13.16-0/target-3.13.16-0-arm64-v8a.zip'
PYTHON_SHA = 'fae8cfd4d3910b6f978b22279fe820c0c6ba49d0ea20f7b106aa6130f1c8f355'


def download(url, expected, destination):
    if not destination.exists():
        with urllib.request.urlopen(url,timeout=90) as response: raw=response.read()
        if hashlib.sha256(raw).hexdigest()!=expected: raise ValueError('Upstream source checksum mismatch')
        destination.write_bytes(raw)
    if hashlib.sha256(destination.read_bytes()).hexdigest()!=expected: raise ValueError('Cached source checksum mismatch')


def main():
    BUILD.mkdir(parents=True,exist_ok=True)
    download(SOURCE_URL,SOURCE_SHA,BUILD/'source.tar.gz')
    download(PYTHON_URL,PYTHON_SHA,BUILD/'python.zip')
    with tarfile.open(BUILD/'source.tar.gz') as archive: archive.extractall(BUILD,filter='data')
    source=BUILD/'rpds_py-0.27.1'
    libs=BUILD/'python-libs';libs.mkdir(exist_ok=True)
    with zipfile.ZipFile(BUILD/'python.zip') as archive:
        (libs/'libpython3.13.so').write_bytes(archive.read('jniLibs/arm64-v8a/libpython3.13.so'))
    sdk=Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk'))))
    tag='darwin-x86_64' if platform.system()=='Darwin' else 'linux-x86_64'
    compiler=sdk/'ndk/25.1.8937393/toolchains/llvm/prebuilt'/tag/'bin/aarch64-linux-android30-clang'
    config=BUILD/'pyo3.txt'
    config.write_text('implementation=CPython\nversion=3.13\nshared=true\nabi3=false\nlib_name=python3.13\nlib_dir='+str(libs)+'\npointer_width=64\n')
    rustc=subprocess.check_output(['rustup','which','--toolchain','1.88.0','rustc'],text=True).strip()
    env=dict(os.environ,PYO3_CONFIG_FILE=str(config),RUSTC=rustc,
             CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=str(compiler),RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384')
    subprocess.run(['rustup','run','1.88.0','cargo','build','--manifest-path',str(source/'Cargo.toml'),'--locked','--release','--target','aarch64-linux-android'],env=env,check=True)
    dist='rpds_py-0.27.1.dist-info'
    contents={
        'rpds/__init__.py':b'from .rpds import *\n',
        'rpds/rpds.cpython-313-aarch64-linux-android.so':(source/'target/aarch64-linux-android/release/librpds.so').read_bytes(),
        'rpds/__init__.pyi':(source/'rpds.pyi').read_bytes(),
        dist+'/METADATA':(source/'PKG-INFO').read_bytes(),
        dist+'/licenses/LICENSE':(source/'LICENSE').read_bytes(),
        dist+'/WHEEL':b'Wheel-Version: 1.0\nGenerator: mcpywrap-android\nRoot-Is-Purelib: false\nTag: cp313-cp313-android_30_arm64_v8a\n',
    }
    record=io.StringIO();writer=csv.writer(record)
    for name,data in contents.items():writer.writerow([name,'sha256='+base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip('='),len(data)])
    writer.writerow([dist+'/RECORD','','']);contents[dist+'/RECORD']=record.getvalue().encode()
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(OUTPUT,'w',zipfile.ZIP_DEFLATED) as wheel:
        for name,data in contents.items():wheel.writestr(name,data)
    print(OUTPUT)

if __name__=='__main__': main()
