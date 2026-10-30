"""One predeclared priority adjustment from the user's original pair-focus plan."""
import json
import os
from pathlib import Path
import time
import numpy as np
import train_expanded as experiment
from train_attention import weights as base_weights
HERE=Path(__file__).resolve().parent


def pair_weights(rows):
    w=base_weights(rows);y=np.array([r['label'] for r in rows])
    for label in sorted(set(y)):
        ix=y==label;mass=.25 if label in ('footstep','hammering') else .125;w[ix]*=mass/w[ix].sum()
    return w*len(rows)/w.sum()


def main():
    experiment.deadline();lock=HERE/'training.lock';fd=os.open(lock,os.O_WRONLY|os.O_CREAT|os.O_EXCL);os.write(fd,json.dumps({'pid':os.getpid(),'recipe':'pair','started':time.time()}).encode());os.close(fd)
    try:
        protocol={'reason':'Restore previously authorized pair priority after existing source-held errors show foot/hammer confusion; not selected with new public test','class_mass':{'footstep':.25,'hammering':.25,'dragging_furniture':.125,'instant_impact':.125,'normal_or_unknown':.125,'vacuum_cleaner':.125},'microphone_native_per_class':[.5,.5],'head':'same frozen-AST attention128','epochs':40,'seed':20261028,'threshold':.65,'new_test_unused':True}
        dest=HERE/'pair-priority-protocol.json'
        if dest.exists():raise RuntimeError('Preserve fixed pair recipe')
        dest.write_text(json.dumps(protocol,indent=2));experiment.weights=pair_weights;experiment.run('pair')
    finally:lock.unlink(missing_ok=True)


if __name__=='__main__':main()
