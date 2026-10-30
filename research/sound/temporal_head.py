"""Recording attention with an explicitly separated cadence/shape feature branch."""
import numpy as np
import torch
from torch import nn


class TemporalAttentionHead(nn.Module):
    def __init__(self):
        super().__init__();self.hidden=nn.Linear(768,128);self.attention=nn.Linear(128,1,bias=False);self.temporal=nn.Sequential(nn.Linear(64,32),nn.Tanh(),nn.Dropout(.25));self.dropout=nn.Dropout(.1);self.classifier=nn.Linear(160,6)

    def forward(self,values,mask,temporal):
        if values.ndim!=3 or values.shape[-1]!=768 or mask.shape!=values.shape[:2] or not bool(mask.any(dim=1).all()):raise ValueError('Each recording needs a valid AST window')
        if temporal.shape!=(values.shape[0],64) or not bool(torch.isfinite(temporal).all()):raise ValueError('Finite64-feature recording cadence required')
        h=torch.tanh(self.hidden(values));a=self.attention(h).squeeze(-1).masked_fill(~mask,float('-inf'));w=torch.softmax(a,dim=1);pooled=torch.sum(h*w.unsqueeze(-1),dim=1)
        return self.classifier(torch.cat([self.dropout(pooled),self.temporal(temporal)],dim=1)),w


def load(path,device='cpu'):
    payload=torch.load(path,map_location='cpu',weights_only=True)
    labels=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner']
    if payload.get('labels')!=labels or abs(float(payload.get('threshold',0))-.65)>1e-12:raise ValueError('Saved label order/threshold does not match the frozen six-class contract')
    if payload.get('architecture') not in ('attention-plus-temporal64','window768-hidden128-tanh-attention128-6','attention-plus-frozen-audioset-prior'):raise ValueError('Unsupported saved model architecture')
    if payload.get('architecture')=='attention-plus-temporal64':model=TemporalAttentionHead()
    elif payload.get('architecture')=='attention-plus-frozen-audioset-prior':
        from semantic_head import SemanticAttentionHead
        model=SemanticAttentionHead(load_only=True)
    else:
        from attention_head import AttentionHead
        model=AttentionHead()
    model.load_state_dict(payload['state_dict']);model.eval().to(device);return payload,model


def predict(state,values,mask,temporal,device='cpu'):
    payload,model=state;x=torch.from_numpy(np.asarray(values,dtype='float32')).to(device);m=torch.from_numpy(np.asarray(mask,dtype=bool)).to(device);x=(x-payload['mean'].to(device))/payload['scale'].to(device)
    with torch.inference_mode():
        if payload.get('architecture')=='attention-plus-temporal64':
            t=torch.from_numpy(np.asarray(temporal,dtype='float32')).to(device);t=(t-payload['temporal_mean'].to(device))/payload['temporal_scale'].to(device);logits,attention=model(x,m,t)
        else:logits,attention=model(x,m)
        return torch.softmax(logits,dim=1).cpu().numpy(),attention.cpu().numpy()
