import unittest
import numpy as np
from sklearn.ensemble import ExtraTreesClassifier
from tf_tree_head import TreeTensorHead
class TreeHeadParityTest(unittest.TestCase):
 def test_matches_sklearn_for_six_classes_and_unseen_feature_vectors(self):
  rng=np.random.default_rng(23);x=rng.normal(size=(180,12)).astype('float32');y=np.array(['c'+str(i%6) for i in range(len(x))])
  clf=ExtraTreesClassifier(n_estimators=12,max_depth=7,random_state=23).fit(x,y)
  head=TreeTensorHead(clf,list(clf.classes_));probe=np.r_[x,rng.normal(size=(30,12)).astype('float32')]
  np.testing.assert_allclose(head(probe).numpy(),clf.predict_proba(probe),rtol=0,atol=1e-7)
 def test_adjacent_float32_values_preserve_a_float64_split_boundary(self):
  low=np.nextafter(np.float32(16),np.float32(17));high=np.nextafter(low,np.float32(17))
  x=np.array([[low],[high]],dtype='float32');clf=ExtraTreesClassifier(n_estimators=1,max_depth=1,random_state=7).fit(x,['a','b'])
  self.assertEqual(3,clf.estimators_[0].tree_.node_count)
  clf.estimators_[0].tree_.threshold[0]=(float(low)+float(high))/2
  head=TreeTensorHead(clf,list(clf.classes_))
  np.testing.assert_array_equal(head(x).numpy(),clf.predict_proba(x))
if __name__=='__main__':unittest.main()
