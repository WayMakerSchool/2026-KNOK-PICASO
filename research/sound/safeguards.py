from collections import Counter
import hashlib
from pathlib import Path
import numpy as np

ALIASES=('parent','pcm','file_hash','source_sha','uploader_key','source_group')


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def ensure_training_open(root):
    if (Path(root)/'fresh_started.json').exists():raise RuntimeError('Final evaluation consumed: no further fitting or candidate selection in this phase')


def tokens(rows):
    return {(k,str(r[k])) for r in rows for k in ALIASES if r.get(k)}


def assert_disjoint(left,right):
    overlap=tokens(left)&tokens(right)
    if overlap:raise ValueError('Source overlap: '+str(sorted(overlap)[:5]))


def normalize_parent_weights(rows):
    count=Counter(r['parent'] for r in rows)
    return np.array([1/count[r['parent']] for r in rows],dtype='float64')


def transfer_folds(base,folds,extra):
    lookup={}
    for r,f in zip(base,folds):
        for token in tokens([r]):lookup.setdefault(token,set()).add(int(f))
    answer=[]
    for r in extra:
        matched=set().union(*(lookup.get(token,set()) for token in tokens([r])))
        if len(matched)!=1:raise ValueError('Restored view needs one established source fold')
        answer.append(matched.pop())
    return np.array(answer,dtype=int)
