import unittest
import numpy as np
from safeguards import assert_disjoint, normalize_parent_weights, transfer_folds


class Integrity(unittest.TestCase):
    def test_derivative_never_crosses_parent_fold(self):
        base=[{'parent':'desk','file_hash':'a','pcm':'p','group':'g'}]
        new=[{'parent':'desk','file_hash':'b','pcm':'q'}]
        self.assertEqual(transfer_folds(base,np.array([2]),new).tolist(),[2])

    def test_same_pcm_is_blocked_despite_changed_header(self):
        with self.assertRaises(ValueError):
            assert_disjoint([{'parent':'one','file_hash':'a','pcm':'p'}],[{'parent':'two','file_hash':'b','pcm':'p'}])

    def test_restore_views_does_not_multiply_parent_mass(self):
        rows=[{'parent':'desk'}]*4+[{'parent':'other'}]
        w=normalize_parent_weights(rows)
        self.assertAlmostEqual(float(w[:4].sum()),float(w[4]))

    def test_unknown_parent_cannot_borrow_arbitrary_fold(self):
        with self.assertRaises(ValueError):
            transfer_folds([{'parent':'one','file_hash':'a','pcm':'p'}],np.array([0]),[{'parent':'two','file_hash':'b','pcm':'q'}])


if __name__=='__main__':unittest.main()
