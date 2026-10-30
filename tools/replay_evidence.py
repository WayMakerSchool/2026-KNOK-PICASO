"""Separate acoustic detection, receiver import and classifier completion evidence."""
import json,time

def stage_outcome(before,after,records,truth):
    same_boot=before.get('bootId')==after.get('bootId')
    prefix=f"{before['deviceId']}-{before['bootId']}-"
    lo=before['startedEvents'];hi=after.get('startedEvents',lo) if same_boot else lo
    matches=[]
    for row in records:
        event=row.get('deviceEventId') or ''
        if event.startswith(prefix) and lo<int(event.rsplit('-',1)[1],16)<=hi:matches.append(row)
    primary=min(matches,key=lambda r:int(r['deviceEventId'].rsplit('-',1)[1],16)) if matches else None
    detected=(hi>lo) if same_boot else None
    imported=bool(primary);pending=bool(primary and primary.get('deviceClassificationPending'))
    failed=bool(primary and primary.get('classificationLabel')=='analysis_failed')
    completed=bool(primary and not pending and not failed and primary.get('classificationLabel') not in ('unclassified',None,''))
    correct=(primary['classificationLabel']==truth) if completed else None
    stage=('boot_changed' if not same_boot else 'detection_timeout' if not detected else
           'import_timeout' if not imported else 'analysis_failed' if failed else
           'analysis_pending_timeout' if not completed else None)
    result={'device_detected':detected,'import_succeeded':imported,'classification_completed':completed,
            'classification_correct':correct,'end_to_end_success':bool(detected and imported and completed and correct),
            'failure_stage':stage,'extra_imported_events':max(0,len(matches)-1)}
    if primary:
        analysis=json.loads(primary.get('soundAnalysisJson') or '{}')
        result.update(primary_event_id=primary['deviceEventId'],predicted=primary['classificationLabel'],
                      inference_ms=analysis.get('inferenceMs'),analysis_generated_epoch_ms=analysis.get('analyzedAt'))
    return result

class TrialJournal:
    def __init__(self,folder,row):self.folder=folder;self.row=row;self.pending=folder/f"trial-{row['trial']:02}-in-progress.json"
    def __enter__(self):
        self.pending.write_text(json.dumps(self.row,ensure_ascii=False,indent=2));return self.row
    def __exit__(self,kind,error,traceback):
        if error:self.row.update(error=str(error),failure_stage=self.row.get('failure_stage') or 'trial_exception')
        self.row['journal_completed_epoch']=time.time()
        with (self.folder/'trials.jsonl').open('a') as f:f.write(json.dumps(self.row,ensure_ascii=False)+'\n')
        self.pending.unlink(missing_ok=True)
        return False

def reconcile(folder,records):
    rows=[json.loads(line) for line in (folder/'trials.jsonl').read_text().splitlines()]
    for row in rows:
        states=row.get('states',[])
        if states and row.get('initial_status'):
            row['reconciled_stages']=stage_outcome(row['initial_status'],states[-1],records,row['truth'])
            row['reconciliation_note']='Final DB resolves eventual import/classification; original timed observation is preserved.'
    (folder/'reconciled-trials.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2));return rows
