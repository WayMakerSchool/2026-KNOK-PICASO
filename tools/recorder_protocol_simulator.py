#!/usr/bin/env python3
"""Explicitly simulated v2 API for Android integration; never hardware evidence."""
import argparse,binascii,json,time,wave,io,re
from http.server import BaseHTTPRequestHandler,HTTPServer
from urllib.parse import urlparse,parse_qs
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--output',required=True)
p.add_argument('--port',type=int,default=49867);p.add_argument('--interval',type=float,default=10)
p.add_argument('--boot-id',default='0000000000000001')
a=p.parse_args();assert re.fullmatch('[0-9a-f]{16}',a.boot_id);out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
with wave.open(a.source,'rb') as f:
    assert (f.getnchannels(),f.getsampwidth(),f.getframerate())==(1,2,16000)
    pcm=f.readframes(min(f.getnframes(),80000))
b=io.BytesIO()
with wave.open(b,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(pcm)
audio=b.getvalue();sample_count=len(pcm)//2;crc=f'{binascii.crc32(audio)&0xffffffff:08x}'
start=time.monotonic();state={'simulated':True,'auto':False,'events':{},'generated':0,'acknowledged':[],
 'statusRequests':0,'requestCount':0,'ackFailureInjected':False,'partialFailureInjected':False,
 'statusFailureInjected':False,'queueOverflows':0,'source':str(Path(a.source).resolve()),'startedAt':time.time()}
last_emit=0
outage_until=0
log=(out/'protocol_requests.jsonl').open('a',buffering=1)
def record(kind,**values):log.write(json.dumps({'elapsed':round(time.monotonic()-start,3),'simulated':True,'kind':kind,**values})+'\n')
def tick():
    global last_emit
    now=time.monotonic()
    if state['auto'] and now-last_emit>=a.interval:
        last_emit=now
        if len(state['events'])>=2:state['queueOverflows']+=1;return
        state['generated']+=1;n=state['generated'];eid=f'000000000000-{a.boot_id}-{n:08x}'
        seq=max(int((now-start)*16000),sample_count+16000)
        event={'id':eid,'completed':True,'simulated':True,'bytes':len(audio),'sampleRate':16000,
          'sampleCount':sample_count,'crc32':crc,'startSample':seq-sample_count,'triggerSample':seq-sample_count+16000,
          'durationMs':sample_count*1000//16000,'rawEdges':4,'digitalEvents':1,
          'lastRawEdgeUs':seq*1000000//16000,'lastDigitalEventUs':seq*1000000//16000,
          'maxLevelDb':95,'averageLevelDb':70,'manual':False,'truncatedAtLimit':False}
        state['events'][eid]=event;record('event_generated',id=eid)
def status():
    return {'protocolVersion':2,'firmwareVersion':'SIMULATED-recorder-v2','simulated':True,
      'device':'SIMULATED-ESP32','deviceId':'000000000000','bootId':a.boot_id,
      'hostname':'simulation.local','ip':'127.0.0.1','wifiConnected':True,'ready':True,'wiringConfirmed':False,
      'recording':False,'finalizing':False,'autoEnabled':state['auto'],'eventQueueAvailable':True,
      'pendingEvents':len(state['events']),'sampleRate':16000,'channels':1,'bitsPerSample':16,
      'durationMs':sample_count*1000//16000,'bytesWritten':len(audio),'hasRecording':bool(state['events']),
      'levelDb':65,'recentPeakDb':95,'thresholdDb':90,'maxLevelDb':95,'averageLevelDb':70,
      'levelSampleBlockCount':int((time.monotonic()-start)*16000/512),'sampleSequence':int((time.monotonic()-start)*16000),
      'digitalEvents':state['generated'],'tiltRawEdges':state['generated']*4,'queueOverflows':state['queueOverflows'],
      'audioOverflows':0,'storageErrors':0,'vibrationIntensityAvailable':False,'levelCalibrated':False,
      'message':'API 모의 시험 · 실물 기기 측정 아님'}
class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.0'
    def log_message(self,*args):pass
    def reply(self,code,body,typ='application/json',headers=None):
        if not isinstance(body,bytes):body=json.dumps(body,ensure_ascii=False).encode()
        self.send_response(code);self.send_header('Content-Type',typ);self.send_header('Content-Length',str(len(body)))
        for k,v in (headers or {}).items():self.send_header(k,v)
        self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_GET(self):self.handle_api()
    def do_POST(self):self.handle_api()
    def handle_api(self):
        global last_emit,outage_until
        begin=time.monotonic();url=urlparse(self.path);q=parse_qs(url.query);path=url.path
        if self.command=='POST':self.rfile.read(int(self.headers.get('Content-Length','0')))
        tick();state['requestCount']+=1;code=200
        if path.startswith('/api/') and time.monotonic()<outage_until:
            code=503;self.reply(code,{'message':'SIMULATED temporary API outage'})
        elif path=='/api/status':
            state['statusRequests']+=1
            if state['statusRequests']==50 and not state['statusFailureInjected']:
                state['statusFailureInjected']=True;code=503;self.reply(code,{'message':'SIMULATED transient failure'})
            else:self.reply(200,status())
        elif path=='/api/auto':
            state['auto']=q.get('enabled',['0'])[0]=='1'
            if state['auto']:last_emit=time.monotonic()-a.interval
            record('auto_control',enabled=state['auto']);self.reply(202,status())
        elif path=='/api/record/stop':self.reply(202,status())
        elif path=='/api/events':self.reply(200,{'protocolVersion':2,'events':list(state['events'].values())})
        elif path=='/api/events/file':
            eid=q.get('id',[''])[0]
            if eid not in state['events']:code=404;self.reply(code,{'message':'event absent'})
            elif eid.endswith('00000002') and not state['partialFailureInjected']:
                state['partialFailureInjected']=True;self.send_response(200)
                self.send_header('Content-Length',str(len(audio)));self.send_header('X-KNOK-Event-ID',eid);self.end_headers()
                self.wfile.write(audio[:1000]);self.wfile.flush();self.close_connection=True
                record('partial_download_injected',id=eid)
            else:
                self.reply(200,audio,'audio/wav',{'X-KNOK-Event-ID':eid});record('wav_download',id=eid)
        elif path=='/api/events/ack':
            eid=q.get('id',[''])[0]
            if eid.endswith('00000001') and not state['ackFailureInjected']:
                state['ackFailureInjected']=True;code=500;self.reply(code,{'message':'SIMULATED lost ACK response'})
            else:
                if eid in state['events']:del state['events'][eid];state['acknowledged'].append(eid)
                self.reply(200,{'acknowledged':True});record('event_acknowledged',id=eid)
        elif path=='/simulation/reboot':
            boot=q.get('bootId',['0000000000000005'])[0]
            if not re.fullmatch('[0-9a-f]{16}',boot):code=400;self.reply(code,{'message':'bad simulated boot'})
            else:
                a.boot_id=boot;state['auto']=False;record('simulated_reboot',bootId=boot);self.reply(200,status())
        elif path=='/simulation/outage':
            seconds=min(30,max(1,float(q.get('seconds',['5'])[0])))
            outage_until=time.monotonic()+seconds;record('simulated_outage',seconds=seconds);self.reply(200,{'outageSeconds':seconds})
        elif path=='/simulation/summary':self.reply(200,{**state,'elapsed':time.monotonic()-start})
        else:code=404;self.reply(code,{'message':'simulation route missing'})
        record('http',path=path,code=code,latencyMs=round((time.monotonic()-begin)*1000,3))
        (out/'simulation_summary.json').write_text(json.dumps({**state,'elapsed':time.monotonic()-start},indent=2))
server=HTTPServer(('127.0.0.1',a.port),Handler)
print(f'SIMULATED API http://127.0.0.1:{a.port} - not physical hardware',flush=True)
try:server.serve_forever()
except KeyboardInterrupt:pass
finally:server.server_close();log.close()
