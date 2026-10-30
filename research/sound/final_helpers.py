import hashlib
import json
from pathlib import Path
import numpy as np
from final_scoring import LABELS,score_trial
HERE=Path(__file__).resolve().parent


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify(lock):
    for name,digest in lock['artifacts'].items():
        if sha(name)!=digest:raise RuntimeError('Frozen artifact changed: '+name)


def trials():
    rows=[r for r in json.loads((HERE/'data/new_public/manifest.json').read_text())['rows'] if r['role']=='sound_test']
    if len(rows)!=120 or any(sum(r['label']==l for r in rows)!=20 for l in LABELS):raise RuntimeError('Frozen120/20perclass denominator changed')
    return rows


def results(rows,probability,good,errors):
    items=[dict(track_id=r['track_id'],parent=r['parent'],kind=r['kind'],family=r['family'],file_hash=r['file_hash'],**score_trial(r['label'],q,bool(ok),error)) for r,q,ok,error in zip(rows,probability,good,errors)]
    if len(items)!=120:raise RuntimeError('Do not drop failed trials')
    matrix={truth:{pred:0 for pred in LABELS+['__processing_failure__']} for truth in LABELS}
    for r in items:matrix[r['truth']][r['prediction'] if r['processing_success'] else '__processing_failure__']+=1
    return {'count':120,'correct':sum(r['correct'] for r in items),'accuracy':sum(r['correct'] for r in items)/120,'processing_success':sum(r['processing_success'] for r in items),'per_class':{l:{'count':20,'correct':sum(r['correct'] for r in items if r['truth']==l),'recall':sum(r['correct'] for r in items if r['truth']==l)/20} for l in LABELS},'confusion':matrix,'rows':items}
