#!/usr/bin/env python3
"""Exercise the public mcpy CLI from an already-paired shared workspace.

Run inside the actual third-party tool environment. Creates only QA-* projects.
A private sitecustomize guard makes any local Core.execute call fail immediately.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--skill',type=Path,required=True)
    args=parser.parse_args()
    workspace=args.workspace.resolve()
    name='QA-Host-'+str(time.time_ns())
    project=workspace/'projects'/name
    project.mkdir(parents=True)
    evidence=workspace/'logs'/('host-client-'+name+'.json')
    results=[]
    with tempfile.TemporaryDirectory(prefix='mcpy-local-guard-') as temporary:
        guard=Path(temporary)/'sitecustomize.py'
        guard.write_text("from importlib.util import find_spec\nif find_spec('mcpywrap_core'):\n    from mcpywrap_core.api import Core\n    def forbidden(*a, **k):\n        raise AssertionError('LOCAL CORE EXECUTION IS FORBIDDEN IN THIS TEST')\n    Core.execute = forbidden\n")
        env=dict(os.environ,PYTHONPATH=temporary,PYTHONIOENCODING='utf-8')
        def invoke(*command,source=project):
            result=subprocess.run([sys.executable,'-m','mcpywrap_core','--project',str(source),
                                   '--non-interactive',*command,'--json'],env=env,capture_output=True,text=True,timeout=900)
            if result.stderr:print(result.stderr,file=sys.stderr,end='')
            data=json.loads(result.stdout)
            results.append({'command':list(command),'exit_code':result.returncode,'result':data})
            evidence.write_text(json.dumps(results,ensure_ascii=False,indent=2))
            if result.returncode or not data.get('ok'):raise ValueError(data)
            if command[0]!='host':
                assert data['execution']=='host',data
                assert data.get('coreVersion'),data
            print('PASS',command[0],data.get('operation_id',''),flush=True)
            return data
        invoke('host','status')
        invoke('init','--name',name)
        invoke('mod','--name','HostProof','--script-dir','HostProof','--client-system','ProofClient','--server-system','ProofServer')
        invoke('build')
        # Real local Addon + JSON Schema validated consumer preparation, from the canonical skill.
        framework=workspace/'projects'/(name+'-framework')
        shutil.copytree(args.skill/'assets/consumer-prepare/framework',framework)
        (project/'data').mkdir()
        (project/'data/items.json').write_text('{"apple": 5, "pear": 8}')
        import tomli,tomli_w
        config=tomli.loads((project/'pyproject.toml').read_text())
        config['tool']['catalog']={'source':'data/items.json'}
        (project/'pyproject.toml').write_text(tomli_w.dumps(config))
        invoke('add','--path','../'+framework.name)
        invoke('sync')
        invoke('script','list','--effective')
        output=Path(invoke('build')['output'])
        assert json.loads((output/'behavior_pack/generated/catalog.json').read_text())=={'apple':5,'pear':8}
        # Tool-only pip dependency must not enter the assembled game pack.
        invoke('add','idna==3.10')
        # Fixed local Git source exercises the same Git acquisition stage without network variability.
        repository=Path(temporary)/'git-origin';repository.mkdir(parents=True)
        (repository/'helper.py').write_text('VALUE = 42\n')
        for command in (['init'],['add','.'],['-c','user.name=Host QA','-c','user.email=qa@example.invalid','commit','-m','fixture']):
            subprocess.run(['git','-C',str(repository),*command],check=True,env=env)
        revision=subprocess.check_output(['git','-C',str(repository),'rev-parse','HEAD'],text=True,env=env).strip()
        # The disposable Git origin is tool-owned; resolved component resources must
        # still be validated and materialized in the App's public cache.
        invoke('add','--git',repository.as_uri(),'--ref',revision,
               '--kind','code','--target','GitProbe','--dep-name','git-probe')
        config=tomli.loads((project/'pyproject.toml').read_text())
        config['tool']['mcpywrap']['scripts']={'stage':{'python':'stage.py'}}
        config['tool']['mcpywrap']['hooks']={event:['stage'] for event in ('before_build','before_package','before_archive')}
        (project/'pyproject.toml').write_text(tomli_w.dumps(config))
        (project/'stage.py').write_text('''import os
from pathlib import Path
root=Path(os.environ['MCPY_PROJECT_ROOT'])
event=os.environ['MCPY_EVENT']
with (root/'stage-events.txt').open('a') as stream:stream.write(event+'\\n')
if event=='before_archive':
    (Path(os.environ['MCPY_PACKAGE_ROOT'])/'behavior_pack/archive.txt').write_text('ARCHIVE_READY')
else:
    (root/'behavior_pack/hook.py').write_text('EVENT = '+repr(event))
''')
        output=Path(invoke('build')['output'])
        assert (output/'behavior_pack/GitProbe/helper.py').read_text()=='VALUE = 42\n'
        assert not list(output.rglob('idna'))
        archive=Path(invoke('package')['artifact'])
        with zipfile.ZipFile(archive) as zipped:
            assert any(n.endswith('/archive.txt') and zipped.read(n)==b'ARCHIVE_READY' for n in zipped.namelist())
        assert (project/'stage-events.txt').read_text().splitlines()==['before_build','before_package','before_archive']
        print('VERIFIED',evidence,flush=True)

if __name__=='__main__':main()
