from pathlib import Path
import tempfile
import unittest
import torch
from temporal_head import load,TemporalAttentionHead
HERE=Path(__file__).resolve().parent
LABELS=['dragging_furniture','footstep','hammering','instant_impact','normal_or_unknown','vacuum_cleaner']


class SavedModelContract(unittest.TestCase):
    def payload(self):return {'state_dict':TemporalAttentionHead().state_dict(),'architecture':'attention-plus-temporal64','labels':LABELS,'threshold':.65}

    def reject(self,payload):
        with tempfile.TemporaryDirectory(dir=HERE) as folder:
            path=Path(folder)/'model.pt';torch.save(payload,path)
            with self.assertRaises(ValueError):load(path)

    def test_wrong_label_order_rejected(self):
        p=self.payload();p['labels']=list(reversed(LABELS));self.reject(p)

    def test_changed_threshold_rejected(self):
        p=self.payload();p['threshold']=.5;self.reject(p)

    def test_unknown_architecture_rejected(self):
        p=self.payload();p['architecture']='unknown-future-format';self.reject(p)


if __name__=='__main__':unittest.main()
