import unittest
import numpy as np
from impact_branch import redistribute


class HierarchyContract(unittest.TestCase):
    def test_other_class_mass_preserved(self):
        q=np.array([[.02,.02,.5,.4,.04,.02]]);r=redistribute(q,np.array([.9]));np.testing.assert_allclose(r[:,[0,1,4,5]],q[:,[0,1,4,5]]);np.testing.assert_allclose(r.sum(1),1,atol=1e-6);self.assertAlmostEqual(float(r[0,2]),.81,delta=1e-6)

    def test_not_an_impact_keeps_original(self):
        q=np.array([[.05,.8,.03,.03,.04,.05]]);np.testing.assert_allclose(redistribute(q,np.array([.9])),q)

    def test_invalid_pair_probability_fails(self):
        with self.assertRaises(ValueError):redistribute(np.array([[.1,.1,.4,.2,.1,.1]]),np.array([float('nan')]))


if __name__=='__main__':unittest.main()
