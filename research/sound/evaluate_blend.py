"""One predeclared equal-probability ensemble; no weight sweep or fitting."""
from pathlib import Path
import json
import numpy as np
from train_restored import metrics
from safeguards import sha
HERE=Path(__file__).resolve().parent


def main():
    dest=HERE/'equal_blend';dest.mkdir(exist_ok=True)
    if (dest/'report.json').exists():raise RuntimeError('Preserve derived candidate')
    members=['expanded_attention','expanded_temporal'];rows=json.loads((HERE/'data/expanded-manifest.json').read_text())['rows'];qs=[];reports=[]
    for name in members:
        with np.load(HERE/name/'oof.npz') as z:
            if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise RuntimeError('Different cohorts cannot be averaged')
            qs.append(z['probabilities'].copy())
        reports.append(json.loads((HERE/name/'report.json').read_text()))
    q=(qs[0]+qs[1])*.5;baseline=reports[0]['baseline_common1779'];after=metrics(rows[:1779],q[:1779]);labels=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner'];reg=[]
    for a,b in zip(reports[0]['regression'],reports[1]['regression']):
        if a['path']!=b['path']:raise RuntimeError('Regression identity mismatch')
        probs=(np.array(a['probabilities'])+np.array(b['probabilities']))*.5;label=labels[int(probs.argmax())] if probs.max()>=.65 else 'normal_or_unknown';reg.append(dict(path=a['path'],parent=a['parent'],truth=a['truth'],prediction=label,correct=label==a['truth'],score_not_accuracy=float(probs.max()),probabilities=probs.tolist()))
    reasons=[]
    if sum(r['correct'] for r in reg)!=3:reasons.append('desk_regression_not3of3')
    if after['domain_class_parent_balanced']<baseline['domain_class_parent_balanced']-.03:reasons.append('balanced_drop_over3points')
    if any(after['per_class'][l]<baseline['per_class'][l]-.05 for l in labels):reasons.append('class_drop_over5points')
    config={'mode':'equal_probability_blend','weights':[.5,.5],'members':[{'path':str(HERE/n/'head.pt'),'sha256':sha(HERE/n/'head.pt')} for n in members],'labels':labels,'threshold':.65,'research_only':True,'backbone_runs_per_window':1,'selected_without_test_scores':True}
    (dest/'ensemble.json').write_text(json.dumps(config,indent=2));report={'scope':'Adaptive development, no new independent KNOK captures','candidate_common1779':after,'baseline_common1779':baseline,'expanded_development':metrics(rows,q),'regression':reg,'regression_correct':sum(r['correct'] for r in reg),'eligible_development':not reasons,'rejection_reasons':reasons,'model_sha256':sha(dest/'ensemble.json'),'new_training_source_held':metrics(rows[1782:],q[1782:])};(dest/'report.json').write_text(json.dumps(report,allow_nan=False,indent=2));np.savez_compressed(dest/'oof.npz',probabilities=q,file_hash=np.array([r['file_hash'] for r in rows]));print('FIXED_BLEND','desk',report['regression_correct'],'balanced',after['domain_class_parent_balanced'],'macro',after['class_balanced'],'eligible',not reasons,flush=True)


if __name__=='__main__':main()
