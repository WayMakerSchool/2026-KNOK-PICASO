"""Vectorized bounded-depth tree inference using only TensorFlow Lite builtins."""
import numpy as np
import tensorflow as tf
class TreeTensorHead(tf.Module):
 def __init__(self,classifier,labels):
  super().__init__();trees=[e.tree_ for e in classifier.estimators_];count=len(trees);nodes=max(t.node_count for t in trees)
  feature=np.zeros((count,nodes),dtype='int32');left=np.full((count,nodes),-1,dtype='int32');right=left.copy();threshold=np.zeros((count,nodes),dtype='float32');prob=np.zeros((count,nodes,len(labels)),dtype='float32')
  indices=[list(classifier.classes_).index(l) for l in labels]
  for i,t in enumerate(trees):
   n=t.node_count;feature[i,:n]=np.maximum(t.feature,0);left[i,:n]=t.children_left;right[i,:n]=t.children_right
   cut=t.threshold.astype('float32');up=cut.astype('float64')>t.threshold;cut[up]=np.nextafter(cut[up],np.float32(-np.inf));threshold[i,:n]=cut
   value=t.value[:,0,indices];prob[i,:n]=value/np.maximum(value.sum(axis=1,keepdims=True),1e-30)
  self.feature=tf.constant(feature);self.left=tf.constant(left);self.right=tf.constant(right);self.threshold=tf.constant(threshold);self.prob=tf.constant(prob);self.tree_count=count;self.depth=max(t.max_depth for t in trees)
 @tf.function
 def __call__(self,x):
  x=tf.convert_to_tensor(x,tf.float32);batch=tf.shape(x)[0];nodes=tf.zeros([batch,self.tree_count],tf.int32);tree=tf.broadcast_to(tf.range(self.tree_count)[None,:],[batch,self.tree_count])
  for _ in range(self.depth):
   index=tf.stack([tree,nodes],axis=2);feature=tf.gather_nd(self.feature,index);values=tf.gather(x,feature,axis=1,batch_dims=1)
   left=tf.gather_nd(self.left,index);right=tf.gather_nd(self.right,index);cut=tf.gather_nd(self.threshold,index)
   nodes=tf.where(left<0,nodes,tf.where(values<=cut,left,right))
  return tf.reduce_mean(tf.gather_nd(self.prob,tf.stack([tree,nodes],axis=2)),axis=1)
