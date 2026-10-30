import unittest
import numpy as np
import torch
from semantic_head import SemanticAttentionHead


class PriorContract(unittest.TestCase):
    def fixture(self):
        return SemanticAttentionHead(np.zeros(768),np.ones(768),{'layernorm.weight':torch.ones(768),'layernorm.bias':torch.zeros(768),'dense.weight':torch.zeros(527,768),'dense.bias':torch.zeros(527)})

    def test_pretrained_semantic_parameters_are_frozen(self):
        model=self.fixture();names=dict(model.named_parameters());self.assertEqual({k for k in names if k.startswith('prior_')},{'prior_strength'});self.assertTrue('prior_dense_weight' in dict(model.named_buffers()))

    def test_finite_six_outputs_and_masked_padding(self):
        torch.manual_seed(20261028);model=self.fixture().eval();x=torch.randn(1,2,768);m=torch.ones((1,2),dtype=torch.bool)
        with torch.no_grad():a,w=model(x,m);b,_=model(torch.cat([x,torch.ones(1,1,768)*100],dim=1),torch.tensor([[True,True,False]]))
        self.assertEqual(a.shape,(1,6));np.testing.assert_allclose(a.numpy(),b.numpy(),atol=1e-6);self.assertTrue(bool(torch.isfinite(a).all()));self.assertAlmostEqual(float(w.sum()),1.,delta=1e-6)


if __name__=='__main__':unittest.main()
