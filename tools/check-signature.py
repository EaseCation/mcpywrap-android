"""Verify the actual APK, including its match with the intended CI signing key."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[1]
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home()/'Library/Android/sdk')
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
output=subprocess.check_output([str(sdk/'build-tools/35.0.0/apksigner'),'verify','--print-certs',str(apk)],text=True)
certificates=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)',output)
assert len(certificates)==1, 'Expected exactly one APK signer'
keystore=os.environ.get('MCPY_CI_DEBUG_KEYSTORE')
if keystore:
    certificate=subprocess.check_output(['keytool','-exportcert','-keystore',keystore,'-alias','androiddebugkey','-storepass','android'])
    expected=hashlib.sha256(certificate).hexdigest()
    assert certificates[0]==expected, 'APK signing certificate does not match the configured CI keystore'
print(json.dumps({'apk':apk.name,'certificate_sha256':certificates[0],'configured_key_verified':bool(keystore)}))
