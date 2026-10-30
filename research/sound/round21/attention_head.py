import numpy as np
import torch
from torch import nn


class AttentionHead(nn.Module):
    def __init__(self):
        super().__init__();self.hidden=nn.Linear(768,128);self.attention=nn.Linear(128,1,bias=False);self.dropout=nn.Dropout(.1);self.classifier=nn.Linear(128,6)

    def forward(self,values,mask):
        if values.ndim!=3 or values.shape[-1]!=768 or mask.shape!=values.shape[:2] or not bool(mask.any(dim=1).all()):raise ValueError('Every recording requires at least one valid AST window')
        hidden=torch.tanh(self.hidden(values));logit=self.attention(hidden).squeeze(-1);logit=logit.masked_fill(~mask,float('-inf'));weights=torch.softmax(logit,dim=1)
        pooled=torch.sum(hidden*weights.unsqueeze(-1),dim=1);return self.classifier(self.dropout(pooled)),weights


def load(path,device='cpu'):
    payload=torch.load(path,map_location='cpu',weights_only=True);model=AttentionHead();model.load_state_dict(payload['state_dict']);model.eval().to(device);return payload,model


def predict(state,values,mask,device='cpu'):
    payload,model=state;x=torch.from_numpy(np.asarray(values,dtype='float32')).to(device);m=torch.from_numpy(np.asarray(mask,dtype=bool)).to(device)
    mean=payload['mean'].to(device);scale=payload['scale'].to(device)
    with torch.inference_mode():logit,attention=model((x-mean)/scale,m);return torch.softmax(logit,dim=1).cpu().numpy(),attention.cpu().numpy()
