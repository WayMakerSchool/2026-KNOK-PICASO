"""Read only the V2 sandbox; preserve a stable DB snapshot and its linked WAVs."""
from pathlib import Path
import io,json,sqlite3,tarfile,time,hashlib
from v2_adb_ui import run,PACKAGE
ROOT=Path(__file__).resolve().parents[2]

def snapshot(name,copy_audio=False):
    folder=ROOT/'private'/name;folder.mkdir(exist_ok=True)
    data=run('exec-out','run-as',PACKAGE,'tar','-cf','-','databases',raw=True)
    (folder/'databases.tar').write_bytes(data)
    with tarfile.open(fileobj=io.BytesIO(data)) as t:t.extractall(folder,filter='data')
    con=sqlite3.connect(folder/'databases/quiet_neighbors_db');con.row_factory=sqlite3.Row
    if con.execute('PRAGMA quick_check').fetchone()[0]!='ok':raise RuntimeError('Unstable DB snapshot; repeat after write completes')
    rows=[dict(r) for r in con.execute('select * from noise_records order by id')];con.close()
    if copy_audio:
        out=ROOT/'evidence/app-recordings';out.mkdir(exist_ok=True)
        for row in rows:
            if not row['recordingPath']:continue
            name=Path(row['recordingPath']).name
            if not name.startswith('esp32_event_'):continue
            wav=run('exec-out','run-as',PACKAGE,'cat','files/'+name,raw=True)
            (out/name).write_bytes(wav)
            row['host_wav_path']=str(out/name);row['wav_sha256']=hashlib.sha256(wav).hexdigest()
    (ROOT/'evidence'/f'{folder.name}-records.json').write_text(json.dumps({'snapshot_epoch':time.time(),'snapshot_requires_quiescent_writer':True,'records':rows},ensure_ascii=False,indent=2))
    return rows

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('name');p.add_argument('--copy-audio',action='store_true');a=p.parse_args()
    rows=snapshot(a.name,a.copy_audio);print(json.dumps([{'id':r['id'],'event':r['deviceEventId'],'label':r['classificationLabel'],'pending':r['deviceClassificationPending']} for r in rows],indent=2))
