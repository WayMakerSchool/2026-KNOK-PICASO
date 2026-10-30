"""Fixed-denominator scoring: processing failures are wrong even for unknown truth."""
import math
LABELS=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner']


def score_trial(truth,probabilities,processing_success,error=None):
    if truth not in LABELS:raise ValueError('Unregistered truth cannot enter final test')
    result={'truth':truth,'processing_success':False,'prediction':None,'correct':False,'score_not_accuracy':None,'abstained':None,'error':error}
    if not processing_success:return result
    try:
        q=[float(v) for v in probabilities]
        if len(q)!=6 or any(not math.isfinite(v) or v<0 or v>1 for v in q) or abs(sum(q)-1)>1e-4:raise ValueError('Invalid six-class probabilities')
    except (TypeError,ValueError,OverflowError) as e:result['error']=str(e);return result
    best=max(range(6),key=q.__getitem__);abstained=q[best]<.65;label='normal_or_unknown' if abstained else LABELS[best]
    result.update(processing_success=True,prediction=label,correct=label==truth,score_not_accuracy=q[best],abstained=abstained,probabilities=q,error=None)
    return result
