"""Freeze 60 unused-in-fitting source files for a reference acoustic replay.

These public reference files already informed model development, and a single
speaker/room is shared: explicitly NOT a pristine independent field test.
"""
from pathlib import Path
import hashlib,json,joblib
ROOT=Path(__file__).resolve().parents[2]
head=joblib.load(ROOT/'models/sound_yamnet_mlp_expanded_verified_sources/calibrated_head.joblib')
catalog=json.loads((ROOT/'models/expanded_data/public_catalog.json').read_text())
training=set(head['fit_source_ids']);trials=[]
for label in head['labels']:
    rows=sorted((r for r in catalog if r['label']==label and r['split']=='reference_validation'),key=lambda r:hashlib.sha256(('20261023-fixed60-'+r['sha256']).encode()).hexdigest())
    selected=[];parents=set()
    for row in rows:
        if row['parent_source_id'] in parents:continue
        if row['parent_source_id'] in training:raise ValueError('Source fitting overlap')
        if hashlib.sha256(Path(row['path']).read_bytes()).hexdigest()!=row['sha256']:raise ValueError('Source SHA mismatch')
        selected.append(row);parents.add(row['parent_source_id'])
        if len(selected)==10:break
    if len(selected)!=10:raise ValueError('Not enough original sources')
    trials.extend(selected)
definition={'scope':'public_reference_sources_held_from_fitting_replayed_after_freeze_not_independent_field_accuracy','model_sha256':json.loads((ROOT/'models/selection.json').read_text())['model_sha256'],'fitting_source_overlap':0,'selection':'Fixed SHA ordering seeded 20261023, ten distinct parents per label; do not tune on these replay results','independent_test':False,'trials':trials}
path=ROOT/'evidence/public-reference-replay-fixed60.json'
if path.exists():raise FileExistsError('Do not replace an already frozen test manifest')
path.write_text(json.dumps(definition,ensure_ascii=False,indent=2));print(path,len(trials))
