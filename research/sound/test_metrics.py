import unittest
import numpy as np
from train_restored import metrics


class PartialCohort(unittest.TestCase):
    def test_missing_classes_are_explicit_not_nan(self):
        rows=[{'label':'hammering','domain':'FSD','group':'one'}];q=np.zeros((1,6));q[0,2]=1
        result=metrics(rows,q)
        self.assertIsNone(result['per_class']['footstep'])
        self.assertEqual(result['evaluated_class_count'],1)
        self.assertEqual(result['class_balanced'],1.)


if __name__=='__main__':unittest.main()
