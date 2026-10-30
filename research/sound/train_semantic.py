import json
import os
from pathlib import Path
import time
import train_expanded as experiment
from train_pair import pair_weights
from semantic_head import MAPPING
HERE=Path(__file__).resolve().parent


def main():
    experiment.deadline();lock=HERE/'training.lock';fd=os.open(lock,os.O_WRONLY|os.O_CREAT|os.O_EXCL);os.write(fd,json.dumps({'pid':os.getpid(),'recipe':'semantic','started':time.time()}).encode());os.close(fd)
    try:
        dest=HERE/'semantic-protocol.json'
        if dest.exists():raise RuntimeError('Preserve semantic recipe')
        dest.write_text(json.dumps({'reason':'Learned small head lost desk domain and still confuses impact classes; test one frozen pretrained AudioSet semantic residual rather than fine-tune backbone or search settings','fixed_mapping':MAPPING,'residual_strength':'One learned sigmoid scalar in[0,1], initialized0.25','class_weights':'pair priority25/25/12.5/12.5/12.5/12.5','epochs':40,'seed':20261028,'threshold':.65,'backbone_updates':0,'fresh_test_unused':True},indent=2));experiment.weights=pair_weights;experiment.run('semantic')
    finally:lock.unlink(missing_ok=True)


if __name__=='__main__':main()
