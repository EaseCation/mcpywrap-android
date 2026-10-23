#!/usr/bin/env python3
"""Run the normal CLI from a paired external app and verify a real dual-side game session."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True,type=Path)
    parser.add_argument('--new',action='store_true',help='Create a new QA instance instead of reopening the existing one')
    args=parser.parse_args();project=args.project.resolve()
    evidence=project.parents[1]/'logs'/('external-runtime-'+project.name)
    evidence.mkdir(exist_ok=True)
    session=None;results=[];original={}
    def invoke(*command):
        completed=subprocess.run([sys.executable,'-m','mcpywrap_core','--project',str(project),'--non-interactive',*command,'--json'],capture_output=True,text=True,timeout=450)
        if completed.stderr:print(completed.stderr,file=sys.stderr,end='')
        result=json.loads(completed.stdout);results.append({'command':command,'result':result})
        (evidence/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
        assert completed.returncode==0 and result.get('ok'),result
        assert result.get('execution')=='host',result
        print('PASS',command[0],result.get('state'),flush=True)
        return result
    try:
        started=invoke('run','--no-gui','--detach',*(['--new'] if args.new else []));session=started['session']
        deadline=time.monotonic()+360
        while True:
            state=invoke('status','--session',session)
            if state.get('state')=='running' and state.get('serverReady') and state.get('pip'):break
            assert state.get('state') not in ('failed','saved','unknown') and time.monotonic()<deadline,state
            time.sleep(2)
        capabilities=invoke('runtime','capabilities','--session',session)
        assert capabilities['python']['client'] and capabilities['python']['server'],capabilities
        for side,system in [('client','ProofClient'),('server','ProofServer')]:
            probe=invoke('runtime','py','--session',session,'--side',side,'--code',
                         "# coding: utf-8\nimport mod.%s.extra%sApi as api\nprint(u'%s端来自外部应用')\n_result = {'level':str(api.GetLevelId()),'system':str(api.GetSystem('HostProof','%s'))}" % (side,side.title(),side,system))
            assert probe['value']['level']=='local-world',probe
            assert probe['value']['system']!='None',probe
            source=project/'behavior_pack/HostProof'/side/(system+'.py')
            original[source]=source.read_bytes()
            source.write_bytes(original[source]+b"\nEXTERNAL_RELOAD_PROOF = 42\n")
            invoke('runtime','reload','python','--session',session,'--side',side,'--file',str(source))
            checked=invoke('runtime','py','--session',session,'--side',side,'--code',
                           "import sys\n_result = sys.modules['HostProof.%s.%s'].EXTERNAL_RELOAD_PROOF"%(side,system))
            assert checked['value']==42
        screenshot=invoke('screenshot','--session',session,'--output',str(evidence/('game-'+str(time.time_ns())+'.png')))
        assert screenshot['width']>1000 and screenshot['height']>500,screenshot
        logs=invoke('logs','--session',session,'--tail','200')
        assert 'client端来自外部应用' in logs['text'] and 'server端来自外部应用' in logs['text'],logs
        print('VERIFIED external dual-side runtime, source reload, logs and full screenshot',flush=True)
    finally:
        for source,content in original.items():source.write_bytes(content)
        if session:
            stopped=invoke('stop','--session',session)
            assert stopped.get('state')=='saved',stopped

if __name__=='__main__':main()
