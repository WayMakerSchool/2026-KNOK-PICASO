"""Controlled speaker replay, with all failed presentations retained in the trial log."""
from pathlib import Path
import json,time
from physical_capture import capture,request
ROOT=Path(__file__).resolve().parents[2]
WAV=Path('/Users/yesung/Documents/Codex/Projects/KNOK_device_test_20261022/wav')
LEGACY=Path('/Users/yesung/Documents/Codex/Projects/KNOK_audio_retraining_20261022/data/legacy')
TRIALS=[]
for label,names in [
 ('footstep',['Walking 1.wav','Walking 2.wav','Running downstairs 1.wav']),
 ('dragging_furniture',['Dragging chair 1.wav','Dragging table 2.wav','Dragging chair 1.wav']),
 ('instant_impact',['Dropping items 1.wav','Dropping items 2.wav','Dropping items 1.wav']),
 ('vacuum_cleaner',['Vacuum sound 1.wav','Vacuum sound 2.wav','Vacuum sound 1.wav']),
 ('normal_or_unknown',['Conversation sound 1.wav','Loud sound from speaker 1.wav','Background noise 1.wav'])]:
 for name in names:TRIALS.append((label,WAV/name))
for name in ['hammering1.wav','hammering2.wav','hammering3.wav']:TRIALS.append(('hammering',LEGACY/name))

def main():
 import argparse,os
 p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--run-name',required=True);p.add_argument('--first',type=int,default=1);p.add_argument('--last',type=int,default=18);a=p.parse_args()
 folder=ROOT/'evidence'/a.run_name;folder.mkdir(exist_ok=True);(folder/'pid.txt').write_text(str(os.getpid()))
 for i,(label,path) in enumerate(TRIALS,1):
  if not(a.first<=i<=a.last):continue
  if (folder/'stop-requested').exists():break
  print('Trial',i,path.name,flush=True)
  row={'trial':i,'requested_truth':label,'source_file':str(path),'started_at':time.time(),'scope':'speaker_replay_development','independent_test':False}
  try:
   record=capture(a.base,path,label,1.0);row['capture_succeeded']=True;row['event_id']=record['event']['id'];row['status_after']=request(a.base,'/api/status')
   if row['status_after'].get('storageErrors') or row['status_after'].get('quarantinedSlots'):raise RuntimeError('Device storage health degraded; abort repeat test')
  except Exception as e:
   row['capture_succeeded']=False;row['error']=str(e)
   with (folder/'trials.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
   raise
  with (folder/'trials.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
  time.sleep(.4)
if __name__=='__main__':main()
