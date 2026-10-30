import unittest
import numpy as np
import torch
from temporal_head import TemporalAttentionHead


class TemporalContract(unittest.TestCase):
    def test_padding_does_not_change_prediction(self):
        torch.manual_seed(20261028);model=TemporalAttentionHead().eval();x=torch.randn(2,2,768);t=torch.randn(2,64);mask=torch.ones((2,2),dtype=torch.bool)
        with torch.no_grad():
            a,_=model(x,mask,t);b,_=model(torch.cat([x,torch.full((2,1,768),9999.)],1),torch.cat([mask,torch.zeros((2,1),dtype=torch.bool)],1),t)
        np.testing.assert_allclose(a.numpy(),b.numpy(),atol=1e-6)

    def test_no_valid_window_is_rejected(self):
        with self.assertRaises(ValueError):TemporalAttentionHead()(torch.randn(1,1,768),torch.zeros((1,1),dtype=torch.bool),torch.zeros((1,64)))

    def test_nonfinite_temporal_features_are_rejected(self):
        with self.assertRaises(ValueError):TemporalAttentionHead()(torch.randn(1,1,768),torch.ones((1,1),dtype=torch.bool),torch.full((1,64),float('nan')))


if __name__=='__main__':unittest.main()
