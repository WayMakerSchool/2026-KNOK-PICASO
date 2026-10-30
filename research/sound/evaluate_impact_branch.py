from pathlib import Path
import json
import sys
import numpy as np
from impact_branch import redistribute
from train_restored import metrics
from safeguards import sha
HERE=Path(__file__).resolve().parent


def main():
    name='impact_C'+sys.argv[1];out=HERE/name
    if (out/'report.json').exists():raise RuntimeError('Preserve branch comparison')
    rows=json.loads((HERE/'data/expanded-manifest.json').read_text())['rows']
    with np.load(HERE/'expanded_semantic/oof.npz') as z:
        if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise ValueError('Six-way cohort identity mismatch')
        baseq=z['probabilities'].copy()
    with np.load(out/'binary-oof.npz') as z:
        if z['file_hash'].tolist()!=[r['file_hash'] for r in rows]:raise ValueError('Binary cohort identity mismatch')
        q=redistribute(baseq,z['hammer_probability']);regp=z['regression_hammer_probability'].copy()
    six=json.loads((HERE/'expanded_semantic/report.json').read_text());labels=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner'];reg=[]
    for r,a in zip(six['regression'],redistribute(np.array([r['probabilities'] for r in six['regression']]),regp)):
        label=labels[int(a.argmax())] if a.max()>=.65 else 'normal_or_unknown';reg.append(dict(path=r['path'],parent=r['parent'],truth=r['truth'],prediction=label,correct=label==r['truth'],score_not_accuracy=float(a.max()),probabilities=a.tolist()))
    before=six['baseline_common1779'];after=metrics(rows[:1779],q[:1779]);reasons=[]
    if sum(r['correct'] for r in reg)!=3:reasons.append('desk_not3of3')
    if after['domain_class_parent_balanced']<before['domain_class_parent_balanced']-.03:reasons.append('balanced_drop_over3points')
    if any(after['per_class'][l]<before['per_class'][l]-.05 for l in labels):reasons.append('class_drop_over5points')
    report={'scope':'Adaptive hierarchical sound development, NOT floor direction','candidate_common1779':after,'baseline_common1779':before,'expanded_development':metrics(rows,q),'new_training_source_held':metrics(rows[1782:],q[1782:]),'regression':reg,'regression_correct':sum(r['correct'] for r in reg),'eligible_development':not reasons,'rejection_reasons':reasons,'model_sha256':sha(out/'branch.joblib'),'six_way_model_sha256':sha(HERE/'expanded_semantic/head.pt'),'group_mass_gate':.8,'threshold':.65};(out/'report.json').write_text(json.dumps(report,allow_nan=False,indent=2));np.savez_compressed(out/'oof.npz',probabilities=q,file_hash=np.array([r['file_hash'] for r in rows]));print('HIERARCHY',name,'desk',report['regression_correct'],'balanced',after['domain_class_parent_balanced'],'macro',after['class_balanced'],'eligible',not reasons,flush=True)


if __name__=='__main__':main()
