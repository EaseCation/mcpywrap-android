#!/usr/bin/env python3
"""Verify a confirmed HTTP cancellation also stops the caller's finite Python script."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True,type=Path)
    args=parser.parse_args();project=args.project.resolve()
    from mcpywrap_core.mobile_host import profiles
    from mcpywrap_core.mobile_host.client import Client
    from mcpywrap_core.mobile_host.receipts import Receipts
    import tomli,tomli_w
    profile=profiles.select(project);assert profile,'Pair this workspace first'
    client=Client(profile);receipts=Receipts(profile)
    known={row['operation_id'] for row in receipts.recent()}
    name='cancel_'+uuid.uuid4().hex[:10]
    source=project/(name+'.py');started=project/(name+'.started');finished=project/(name+'.finished')
    original=(project/'pyproject.toml').read_bytes()
    source.write_text("import time\nfrom pathlib import Path\nPath(%r).write_text('started')\ntime.sleep(30)\nPath(%r).write_text('finished')\n"%(started.name,finished.name))
    configuration=tomli.loads(original.decode())
    configuration['tool']['mcpywrap'].setdefault('scripts',{})[name]={'python':source.name,'timeout':45}
    (project/'pyproject.toml').write_text(tomli_w.dumps(configuration))
    process=None
    try:
        process=subprocess.Popen([sys.executable,'-m','mcpywrap_core','--project',str(project),'--non-interactive','script','run',name,'--json'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        deadline=time.monotonic()+30
        while not started.exists():
            assert process.poll() is None,'Script failed before running'
            assert time.monotonic()<deadline,'Script did not start'
            time.sleep(.2)
        row=next(row for row in receipts.recent() if row['operation_id'] not in known and row['operation']=='script.run')
        identity=row['operation_id']
        client.request('POST','/operations/'+identity+'/cancel',{})
        output,errors=process.communicate(timeout=20)
        assert process.returncode!=0 and not finished.exists(),(output,errors)
        state=client.request('GET','/operations/'+identity)
        assert state['state']=='interrupted',state
        print(json.dumps({'cancelled':True,'script_stopped':True,'operation_id':identity}))
    finally:
        if process and process.poll() is None:process.terminate();process.wait(timeout=10)
        (project/'pyproject.toml').write_bytes(original)
        source.unlink(missing_ok=True)

if __name__=='__main__':main()
