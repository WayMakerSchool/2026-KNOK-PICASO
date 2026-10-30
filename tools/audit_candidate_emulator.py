#!/usr/bin/env python3
"""Audit isolated .candidate app; no original-package data is modified."""
import argparse,subprocess,tarfile,io,sqlite3,json,hashlib,csv,wave,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--backup',required=True);p.add_argument('--output',required=True)
p.add_argument('--stable',action='store_true',help='Stop candidate before consistent final snapshot')
p.add_argument('--serial',default='emulator-5556');a=p.parse_args()
b=Path(a.backup);out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
package='com.aistudio.quietneighbors.gspqzy.candidate'
adb='/Users/yesung/Library/Android/sdk/platform-tools/adb'
def run(args):return subprocess.check_output([adb,'-s',a.serial]+args)
if a.stable:run(['shell','am','force-stop',package])
data=run(['exec-out','run-as',package,'tar','-cf','-','databases'])
with tarfile.open(fileobj=io.BytesIO(data)) as t:t.extractall(out/'snapshot',filter='data')
dbpath=out/'snapshot/databases/quiet_neighbors_db'
Path(str(dbpath)+'-shm').unlink(missing_ok=True)
c=sqlite3.connect(dbpath);c.row_factory=sqlite3.Row
assert c.execute('pragma quick_check').fetchone()[0]=='ok'
old=sqlite3.connect(b/'database_audit/databases/quiet_neighbors_db');old.row_factory=sqlite3.Row
cols=[r[1] for r in old.execute('pragma table_info(noise_records)')]
originals=list(old.execute('select * from noise_records order by id'))
preserved=all(tuple(c.execute('select '+','.join(cols)+' from noise_records where id=?',(r['id'],)).fetchone())==tuple(r) for r in originals)
rows=list(c.execute("select * from noise_records where deviceEventId like '000000000000-%' order by id"))
records=[];all_valid=True
for r in rows:
    path=r['recordingPath'];relative='files/'+path.split('/files/',1)[1]
    audio=run(['exec-out','run-as',package,'cat',relative]);meta=json.loads(r['deviceMetadataJson'])
    valid=len(audio)==44+2*r['deviceSampleCount']
    import binascii
    valid=valid and f'{binascii.crc32(audio)&0xffffffff:08x}'==meta['crc32'].lower()
    with wave.open(io.BytesIO(audio),'rb') as w:valid=valid and (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
    valid=valid and '모의' in r['note'] and meta['simulated']
    all_valid=all_valid and valid
    records.append({'condition':'API_SIMULATION_NOT_ACOUSTIC_TEST','eventId':r['deviceEventId'],
      'source':str(b/'device_latest_before.wav'),'firmwareVersion':'SIMULATED-recorder-v2',
      'appValidationStage':('endurance_63dbbc3_before_review_fixes' if '-0000000000000001-' in r['deviceEventId'] else 'intermediate_review_fixed_smoke_91bd5ca' if '-0000000000000002-' in r['deviceEventId'] else 'final_transport_recovery_smoke_79f8c62'),
      'modelVersion':'knok-interfloor-v1 (existing 501D)','expectedSoundLabel':'',
      'classificationLabel':r['classificationLabel'],'classificationConfidence':r['classificationConfidence'],
      'sampleCount':r['deviceSampleCount'],'durationMs':r['durationMs'],'digitalEvents':r['deviceDigitalEvents'],
      'rawEdges':r['deviceRawEdges'],'wavVerified':valid,'classificationPending':bool(r['deviceClassificationPending']),
      'detectionLatencyMs':'','httpP50Ms':'','httpP95Ms':'','audioSha256':hashlib.sha256(audio).hexdigest()})
summary={'stableSnapshot':a.stable,'databaseVersion':c.execute('pragma user_version').fetchone()[0],
 'originalRecords':len(originals),'originalRecordMetadataPreserved':preserved,'simulatedRecords':len(rows),
 'uniqueSimulatedIds':len({r['deviceEventId'] for r in rows}),
 'classifiedSimulatedRecords':sum(r['classificationLabel']!='unclassified' and not r['deviceClassificationPending'] for r in rows),
 'pendingClassification':sum(bool(r['deviceClassificationPending']) for r in rows),'allSimulatedWavValid':all_valid}
(out/'candidate_audit.json').write_text(json.dumps(summary,indent=2))
if records:
    with (out/'comparison_results.csv').open('w',newline='',encoding='utf-8-sig') as f:
        writer=csv.DictWriter(f,fieldnames=list(records[0]));writer.writeheader();writer.writerows(records)
print(json.dumps(summary))
c.close();old.close()
