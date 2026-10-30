"""Frozen AudioSet semantic evidence as a bounded residual to the learned six-way head."""
import json
import math
from pathlib import Path
import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
MODEL=Path(__file__).resolve().parent.parent/'silent_root_cause_20261025/ast-model'
MAPPING=[['Scrape','Scratch'],['Walk, footsteps','Run'],['Hammer','Knock','Tap'],['Coin (dropping)','Thump, thud','Bang','Basketball bounce'],['Speech','Water','Rain','Wind','Music'],['Vacuum cleaner']]


class SemanticAttentionHead(nn.Module):
    def __init__(self,mean=None,scale=None,prior_tensors=None,load_only=False):
        super().__init__();self.hidden=nn.Linear(768,128);self.attention=nn.Linear(128,1,bias=False);self.dropout=nn.Dropout(.1);self.classifier=nn.Linear(128,6);self.prior_strength=nn.Parameter(torch.tensor(math.log(.25/.75)))
        config=json.loads((MODEL/'config.json').read_text());lookup={label:int(i) for i,label in config['id2label'].items()};self.groups=[[lookup[name] for name in names] for names in MAPPING];self.eps=config.get('layer_norm_eps',1e-12)
        self.register_buffer('input_mean',torch.tensor(np.zeros(768) if mean is None else mean,dtype=torch.float32));self.register_buffer('input_scale',torch.tensor(np.ones(768) if scale is None else scale,dtype=torch.float32))
        if prior_tensors is None:
            prior_tensors={k:torch.zeros(shape) for k,shape in [('layernorm.weight',(768,)),('layernorm.bias',(768,)),('dense.weight',(527,768)),('dense.bias',(527,))]}
            if not load_only:
                from safetensors import safe_open
                with safe_open(MODEL/'model.safetensors',framework='pt') as f:prior_tensors={k:f.get_tensor('classifier.'+k).float() for k in prior_tensors}
        for k,v in prior_tensors.items():self.register_buffer('prior_'+k.replace('.','_'),v.clone().float())

    def forward(self,values,mask):
        if values.ndim!=3 or values.shape[-1]!=768 or mask.shape!=values.shape[:2] or not bool(mask.any(dim=1).all()):raise ValueError('Each recording requires a valid AST window')
        hidden=torch.tanh(self.hidden(values));a=self.attention(hidden).squeeze(-1).masked_fill(~mask,float('-inf'));weights=torch.softmax(a,dim=1);pooled=torch.sum(hidden*weights.unsqueeze(-1),dim=1)
        raw=values*self.input_scale+self.input_mean;normalized=F.layer_norm(raw,(768,),self.prior_layernorm_weight,self.prior_layernorm_bias,self.eps);logits=F.linear(normalized,self.prior_dense_weight,self.prior_dense_bias);prior=torch.stack([logits[:,:,ids].max(dim=-1).values for ids in self.groups],dim=-1);prior=torch.sum(prior*weights.unsqueeze(-1),dim=1)
        return self.classifier(self.dropout(pooled))+torch.sigmoid(self.prior_strength)*prior,weights
