from pathlib import Path
import json
from datetime import datetime
from zoneinfo import ZoneInfo
import numpy as np
from safeguards import sha,ensure_training_open
from train_restored import metrics
from final_helpers import trials
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'wednesday_two_models_20261028/tuesday_public_20261027'


def main():
    ensure_training_open(HERE);dest=HERE/'final-lock.json'
    if dest.exists():raise RuntimeError('Preserve frozen selection')
    rows=json.loads((HERE/'data/expanded-manifest.json').read_text())['rows'];common=rows[:1779]
    with np.load(OLD/'round21/outputs/oof.npz') as z:
        if z['file_hash'].tolist()!=[r['file_hash'] for r in common]:raise RuntimeError('Baseline identity/order mismatch')
        baseline=metrics(common,z['probabilities'])
    candidates=[]
    for name in ['restored','expanded_attention','expanded_temporal','equal_blend','expanded_pair','expanded_semantic','impact_C1','impact_C10']:
        report=json.loads((HERE/name/'report.json').read_text())
        with np.load(HERE/name/'oof.npz') as z:
            if z['file_hash'].tolist()[:1779]!=[r['file_hash'] for r in common]:raise RuntimeError('Candidate comparison cohort changed')
            m=metrics(common,z['probabilities'][:1779])
        eligible=report.get('eligible_development',True) and report['regression_correct']==3
        candidates.append({'name':name,'metrics':m,'desk_regression_same_parent':report['regression_correct'],'eligible':eligible})
    ranked=sorted([r for r in candidates if r['eligible']],key=lambda r:(r['metrics']['domain_class_parent_balanced'],r['metrics']['class_balanced']),reverse=True)
    if not ranked:raise RuntimeError('No candidate passes development guardrails')
    selected=ranked[0]
    if selected['name']!='expanded_semantic':raise RuntimeError('Add explicit export/inference support before freezing a different selected architecture')
    test=trials();backups=json.loads((HERE/'backups.json').read_text())
    for r in backups:
        if sha(r['source'])!=r['sha256'] or sha(r['backup'])!=r['sha256']:raise RuntimeError('Preserved source or backup changed')
    files=list(HERE.glob('*.py'))+[HERE/f for f in ['PLAN.txt','CANDIDATE_PROTOCOL.txt','IMPACT_PROTOCOL.txt','DATA_QUALITY_CORRECTIONS.txt','review_findings.txt','semantic-protocol.json','pair-priority-protocol.json','data/ratings-interpretation-audit.json','data/expanded-manifest.json','data/expanded-bags.npz','data/new_public/selected.json','data/new_public/manifest.json','expanded_semantic/head.pt','source_snapshots/before-semantic.json']]
    files += [OLD/'round21/attention_head.py',OLD/'round21/extract_bags.py',OLD/'round14/experiment_core.py',OLD/'round14/build_pool.py',OLD/'round19/data/manifest.json',OLD/'round21/data/bags.npz',OLD/'round21/outputs/oof.npz',OLD/'round21/outputs/attention-head.pt',OLD.parent/'ast_worker.py',HERE.parent/'silent_root_cause_20261025/silent_preprocess.py',HERE.parent/'silent_root_cause_20261025/portable_head.py',HERE.parent/'silent_root_cause_20261025/ast-model/config.json',HERE.parent/'silent_root_cause_20261025/ast-model/model.safetensors',HERE.parent/'silent_root_cause_20261025/research-head.npz',HERE/'backups/preserved_v6.tflite']
    for r in test:
        if sha(r['path'])!=r['file_hash']:raise RuntimeError('Frozen test WAV changed')
    lock={'status':'selected_and_frozen_before_any_fresh120_inference','frozen_at':datetime.now(ZoneInfo('Asia/Seoul')).isoformat(),'selected':selected,'baseline_common1779':baseline,'all_adaptive_candidates':candidates,'selection_rule':'Highest common1779domain/class/parent-balanced score among3/3desk/no>3pointmean/no>5pointclass-drop eligible candidates; then macro recall','model_path':str(HERE/'expanded_semantic/head.pt'),'mode':'semantic_attention','threshold':.65,'baselines':{'V6':str(HERE/'backups/preserved_v6.tflite'),'AST_legacy':str(HERE.parent/'silent_root_cause_20261025/research-head.npz'),'R21':str(OLD/'round21/outputs/attention-head.pt')},'primary_test_count':120,'required_over85_correct':103,'scope':'70 uploader-separated FSD clips plus50new apartment event/files from KNOWN buildings AND KNOWN emitting positions. Public proxy only, no new KNOK/ceiling/miniature/real-home accuracy.','sensitivity_FSD_unanimous_ids':[r['track_id'] for r in test if r['kind']=='FSD' and not any(v['track_id']==r['track_id'] and v['np_count'] for v in json.loads((HERE/'data/ratings-interpretation-audit.json').read_text())['rows'])],'artifacts':{str(p):sha(p) for p in files}}
    dest.write_text(json.dumps(lock,ensure_ascii=False,allow_nan=False,indent=2));print('FINAL_LOCKED',selected['name'],'development',selected['metrics']['domain_class_parent_balanced'],'desk3/3','test120stillunused','sha',sha(dest),flush=True)


if __name__=='__main__':main()
