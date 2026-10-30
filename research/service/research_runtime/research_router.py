"""Choose V6 only for a confident vacuum; otherwise use frozen AST R1."""
import math


class HybridSound:
    model_version='knok-research-hybrid-v6-vacuum-ast-r1-20261027'

    def __init__(self,v6_model,r1_model):
        self.v6_model=v6_model;self.r1_model=r1_model

    @property
    def ready(self):
        return bool(self.v6_model.ready and self.r1_model.ready)

    def predict(self,path):
        first=self.v6_model.predict(path)
        if first.get('label') not in {'dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner'}:
            raise RuntimeError('V6 returned an invalid label')
        if first.get('signal_quality') not in {'normal','low_signal','quantization_limited'}:
            raise RuntimeError('V6 returned an invalid signal-quality state')
        score=first.get('score')
        if not isinstance(score,(int,float)) or not math.isfinite(score) or not 0<=score<=1:
            raise RuntimeError('V6 returned an invalid score')
        use_v6=(first.get('label')=='vacuum_cleaner' and score>=.65 and first.get('signal_quality')=='normal')
        result=dict(first if use_v6 else self.r1_model.predict(path))
        if not use_v6:
            result['inference_ms']=max(0,int(first.get('inference_ms',0)))+max(0,int(result.get('inference_ms',0)))
        result['model_version']=self.model_version+('-v6' if use_v6 else '-r1')
        return result
