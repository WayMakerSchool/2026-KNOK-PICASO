#!/usr/bin/env python3
"""Visible, isolated Android candidate test against the labelled local simulator."""
import argparse,json,subprocess,time,re,xml.etree.ElementTree as ET,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--port',type=int,default=49868)
a=p.parse_args();out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
adb='/Users/yesung/Library/Android/sdk/platform-tools/adb';pkg='com.aistudio.quietneighbors.gspqzy.candidate';base=f'http://127.0.0.1:{a.port}'
def run(args):return subprocess.check_output([adb,'-s','emulator-5556']+args,stderr=subprocess.STDOUT)
def ui():
    run(['shell','input','keyevent','KEYCODE_WAKEUP']);run(['shell','wm','dismiss-keyguard'])
    for attempt in range(3):
        run(['shell','uiautomator','dump','/sdcard/knok-validation.xml'])
        raw=run(['exec-out','cat','/sdcard/knok-validation.xml'])
        try:return ET.fromstring(raw)
        except ET.ParseError:time.sleep(.5)
    raise RuntimeError('Emulator UI is not ready; no blind button input was sent.')
def texts(root):return [n.get('text') for n in root.iter('node') if n.get('text')]
def tap(label):
    root=ui()
    for node in root.iter('node'):
        if node.get('text')==label and node.get('enabled')=='true':
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            run(['shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)]);return
    raise RuntimeError('Expected candidate control unavailable: '+label+'; texts='+repr(texts(root)))
def get(path):
    with urllib.request.urlopen(base+path,timeout=3) as response:return json.load(response)
def wait_for(predicate,seconds=12):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        s=get('/simulation/summary')
        if predicate(s):return s
        time.sleep(.3)
    raise RuntimeError('Simulated API condition did not become true')
result={'simulated':True,'physicalHardwareTest':False,'package':pkg,'steps':[]}
run(['reverse',f'tcp:{a.port}',f'tcp:{a.port}'])
run(['shell','am','start','-n',pkg+'/com.example.MainActivity'])
root=ui()
if '상시 녹음 시작' not in texts(root):tap('상시녹음')
root=ui()
if '상시 녹음 시작' in texts(root):tap('상시 녹음 시작')
s=wait_for(lambda s:s['auto'] and len(s['acknowledged'])>=1)
result['steps'].append({'case':'start_and_import','pass':True,'acked':len(s['acknowledged'])});print('start/import OK',flush=True)
before=len(s['acknowledged']);get('/simulation/outage?seconds=8');time.sleep(8.5)
s=wait_for(lambda s:s['auto'] and len(s['acknowledged'])>before,18)
assert '상시 녹음 중지' in texts(ui()),'App must retain the requested session after temporary outage'
result['steps'].append({'case':'8_second_simulated_outage','pass':True,'acked':len(s['acknowledged'])});print('8s outage recovery OK',flush=True)
get('/simulation/reboot?bootId=0000000000000005')
s=wait_for(lambda s:s['auto'],8)
assert '상시 녹음 중지' in texts(ui())
result['steps'].append({'case':'auto_restored_after_simulated_boot_change','pass':True})
print('reboot auto restore OK',flush=True)
wait_for(lambda s:any('-0000000000000005-' in x for x in s['acknowledged']),12)
tap('상시 녹음 중지');s=wait_for(lambda s:not s['auto'] and not s['events'])
result['steps'].append({'case':'stop_and_drain','pass':True,'generated':s['generated'],'acked':len(s['acknowledged'])})
result['finalApi']=s
(out/'final_candidate_protocol_check.json').write_text(json.dumps(result,indent=2,ensure_ascii=False))
(out/'final-candidate-stopped.png').write_bytes(run(['exec-out','screencap','-p']))
print(json.dumps({'allPassed':True,'generated':s['generated'],'acknowledged':len(s['acknowledged'])}),flush=True)
