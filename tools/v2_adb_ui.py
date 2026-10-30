"""ADB helpers restricted to the separate V2 package and a fresh accessibility dump."""
import subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
ADB='/Users/yesung/Library/Android/sdk/platform-tools/adb'
PACKAGE='com.aistudio.quietneighbors.gspqzy.v2'
def run(*args,raw=False):return subprocess.check_output([ADB,'-s','emulator-5554',*args],text=not raw)
def ensure_v2():
 activity=run('shell','dumpsys','activity','activities')
 current=next((s for s in activity.splitlines() if 'topResumedActivity=' in s),'')
 if PACKAGE+'/' not in current:raise RuntimeError('V2 is not the foreground app; input refused')
def tree():
 ensure_v2()
 for _ in range(3):
  run('shell','rm','-f','/sdcard/knok-v2-ui.xml')
  result=run('shell','uiautomator','dump','/sdcard/knok-v2-ui.xml')
  if 'dumped to:' in result:
   return ET.fromstring(run('exec-out','cat','/sdcard/knok-v2-ui.xml'))
  time.sleep(.5)
 raise RuntimeError('Fresh V2 UI dump unavailable')
def tap_text(text):
 nodes=[n for n in tree().iter('node') if n.get('text')==text or n.get('content-desc')==text]
 if not nodes:raise RuntimeError('V2 control absent: '+text)
 x1,y1,x2,y2=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
 run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def texts():return [n.get('text') for n in tree().iter('node') if n.get('text')]
def screenshot(path):
 ensure_v2();Path(path).write_bytes(run('exec-out','screencap','-p',raw=True))
