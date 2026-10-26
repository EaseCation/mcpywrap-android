"""Drive System UI PiP controls while PipExpansionTest owns the QA game session.

Start PipExpansionTest with tools/verify-device.sh in another terminal.
Wait for /sdcard/mcpy/logs/pip-expansion/driver-ready, then run this script
with ANDROID_SERIAL set. Optional first argument: cycle count (default 5).
MCPY_WAIT_RUNNING=1 tests gameplay; otherwise it also tests initial loading.
This checks real window focus and dimensions, never starts/restarts a game.
"""
import subprocess,time,json,re,pathlib,sys,os,xml.etree.ElementTree as ET
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Library/Android/sdk'))
adb=[os.environ.get('ADB',str(sdk/'platform-tools/adb')),'-s',os.environ.get('ANDROID_SERIAL','emulator-5564')]
out=pathlib.Path(__file__).resolve().parents[1]/'artifacts/pip-expansion';out.mkdir(parents=True,exist_ok=True)
def run(*a):return subprocess.check_output(adb+list(a))
def shell(s):return run('shell',s).decode()
a=json.loads(run('exec-out','cat','/sdcard/mcpy/logs/active.json'));assert a['project'].startswith(('QA-BackNavigation-','QA-PipExpansion-'))
s=a['session']
assert run('exec-out','cat','/sdcard/mcpy/logs/pip-expansion/driver-ready').decode().strip()==s
def status():return json.loads(run('exec-out','cat',s+'/status.json'))
def wait(label,pred,seconds=30):
 end=time.time()+seconds
 while time.time()<end:
  if pred():return
  time.sleep(.25)
 raise RuntimeError(label+' '+str(status()))
def window():
 text=shell('dumpsys window windows')
 for w in re.split(r'  Window #\d+',text):
  if 'com.mojang.minecraftpe.MainActivity}' in w.split('\n')[0]:return w
 raise RuntimeError('no game window')
def bounds():
 m=re.search(r' frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]',window());assert m
 return tuple(map(int,m.groups()))
def shot(name):
 b=run('exec-out','screencap','-p');(out/(name+'.png')).write_bytes(b[b.index(b'\x89PNG\r\n\x1a\n'):])
def full():
 st=status()
 return not st['pip'] and st['viewWidth']==st['bufferWidth'] and 'com.mojang.minecraftpe.MainActivity}' in shell('dumpsys window | grep mCurrentFocus')
if os.environ.get('MCPY_WAIT_RUNNING') == '1':
 wait('world running before expansion',lambda:status()['phase']=='running',300)
for i in range(int(sys.argv[1]) if len(sys.argv)>1 else 5):
 wait('PiP',lambda:status()['pip'])
 before=status();x,y,X,Y=bounds();cx=(x+X)//2;cy=(y+Y)//2
 run('shell','input','tap',str(cx),str(cy));time.sleep(.8)
 run('shell','input','tap',str(cx),str(cy))
 wait('fullscreen with focus',full)
 time.sleep(float(os.environ.get('MCPY_FULLSCREEN_SETTLE','2')))
 assert full(),'lost full screen after expansion'
 assert 'isVisible=true' in window()
 (out/f'cycle-{i}-fullscreen.json').write_text(json.dumps(status(),indent=2))
 assert status()['pid']==before['pid'], 'Game process was replaced'
 print('FULLSCREEN',i,status()['phase'],status().get('pipEntry'),flush=True)
 if i in (0,4):shot(f'cycle-{i}-fullscreen')
 # Actual adapter button, found from the current full-screen UI.
 shell('uiautomator dump /sdcard/window.xml')
 root=ET.fromstring(run('exec-out','cat','/sdcard/window.xml'))
 n=next(n for n in root.iter('node') if n.get('content-desc')=='缩小为游戏浮窗')
 x,y,X,Y=map(int,re.findall(r'\d+',n.get('bounds')))
 run('shell','input','tap',str((x+X)//2),str((y+Y)//2))
 wait('next PiP entry',lambda:status()['pip'] and status().get('pipEntry',0)>before.get('pipEntry',0))
 wait('caller restored',lambda:'com.mojang.minecraftpe.MainActivity}' not in shell('dumpsys window | grep mCurrentFocus'))
 time.sleep(.6)
 assert json.loads(run('exec-out','cat','/sdcard/mcpy/logs/active.json'))['session']==s
 print('PIP',i,status().get('pipEntry'),flush=True)
wait('world running',lambda:status()['phase']=='running',240)
shot('final-pip')
(out/'session.json').write_text(json.dumps(a,indent=2))
shell('echo passed > /sdcard/mcpy/logs/pip-expansion/driver-complete.tmp && mv /sdcard/mcpy/logs/pip-expansion/driver-complete.tmp /sdcard/mcpy/logs/pip-expansion/driver-complete')
print('PASSED',flush=True)
