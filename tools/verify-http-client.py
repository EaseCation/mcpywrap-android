#!/usr/bin/env python3
"""Verify the public API without shared directory access, CLI imports, or a resident agent."""
import argparse
import json
import time
import uuid
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError
from urllib.parse import urlencode


def main():
    args=argparse.ArgumentParser(description=__doc__)
    args.add_argument('--url',default='http://127.0.0.1:17653')
    args.add_argument('--code',required=True)
    options=args.parse_args()
    opener=build_opener(ProxyHandler({}));token=None
    def request(method,path,data=None):
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        message=Request(options.url+'/v2'+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
        with opener.open(message,timeout=30) as response:result=json.load(response)
        assert result.get('ok',True),result
        return result
    pending=request('POST','/pairings',{'code':options.code,'label':'Generic HTTP QA','access':'files'})
    assert 'probe' not in pending
    print('Approve Generic HTTP QA in the App.',flush=True)
    deadline=time.monotonic()+300
    while True:
        pair=request('POST','/pairings/'+pending['id']+'/result',{'secret':pending['secret']})
        if pair['state']=='approved':token=pair['token'];break
        assert pair['state']=='pending' and time.monotonic()<deadline,pair['state']
        time.sleep(.5)
    project='projects/QA-HTTP-Client-'+uuid.uuid4().hex[:10]
    def wait(identity):
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            job=request('GET','/operations/'+identity)
            if job['state'] in ('completed','failed','interrupted','unknown'):return job
            time.sleep(.2)
        raise RuntimeError('Operation result unknown: '+identity)
    def operation(name,parameters):
        payload={'request_id':uuid.uuid4().hex,'project':project,'operation':name,'parameters':parameters}
        job=request('POST','/operations',payload)
        assert request('POST','/operations',payload)['operation_id']==job['operation_id']
        result=wait(job['operation_id']);assert result['state']=='completed',result
        print('PASS',name,flush=True);return result['result']
    operation('init',{'name':project.split('/')[-1]})
    operation('mod',{'name':'HttpDemo','script_dir':'HttpDemo'})
    original=request('GET','/files?'+urlencode({'project':project,'path':'behavior_pack/HttpDemo/modMain.py'}))
    write={'request_id':uuid.uuid4().hex,'project':project,'path':'behavior_pack/HttpDemo/modMain.py','digest':original['digest'],'text':original['text']+'\n# HTTP editor\n'}
    identity=request('PUT','/files',write)['operation_id'];assert wait(identity)['state']=='completed'
    assert request('PUT','/files',write)['operation_id']==identity
    stale=dict(write,request_id=uuid.uuid4().hex,text='# stale')
    assert wait(request('PUT','/files',stale)['operation_id'])['state']=='failed'
    print('PASS file conflict and replay',flush=True)
    built=operation('build',{});assert built.get('coreVersion')
    first=operation('instances.create',{'name':'First'})
    second=operation('instances.create',{'name':'Second'})
    assert first['project_id']==second['project_id']
    operation('instances.trash',{'instance':second['id']})
    assert len(operation('instances.list',{})['instances'])==1
    renamed=operation('projects.rename',{'name':project.split('/')[-1]+'-renamed'})
    project=renamed['path'];assert renamed['id']==first['project_id']
    operation('projects.trash',{})
    request('POST','/clients/revoke',{})
    try:request('GET','/projects')
    except HTTPError as error:assert error.code==401
    else:raise AssertionError('Revoked token was accepted')
    print('VERIFIED generic HTTP workflow',flush=True)

if __name__=='__main__':main()
