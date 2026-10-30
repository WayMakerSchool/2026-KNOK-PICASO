"""TensorFlow version of the existing normalized 501 acoustic feature equations."""
import tensorflow as tf
import numpy as np
from knok_retrain.acoustic import WINDOW,MEL_FILTER_BANK,DCT_BASIS

def gradient(x):return tf.concat([x[:,1:2]-x[:,:1],(x[:,2:]-x[:,:-2])*.5,x[:,-1:]-x[:,-2:-1]],axis=1)
def statistics(x):
 sorted_x=tf.sort(x,axis=1)
 return tf.concat([tf.reduce_mean(x,axis=1),tf.math.reduce_std(x,axis=1),sorted_x[:,20],sorted_x[:,180]],axis=0)
def acoustic_501(samples):
 x=tf.convert_to_tensor(samples,tf.float32)
 padded=tf.pad(x,[[512,512]],mode='REFLECT')
 frames=tf.signal.frame(padded,1024,320,pad_end=False)
 windowed=frames*tf.constant(WINDOW)
 fft=tf.signal.rfft(windowed,[1024]);magnitude=tf.abs(fft);power=(magnitude*magnitude)/float(np.sum(WINDOW**2))
 mel=tf.linalg.matmul(power,tf.constant(MEL_FILTER_BANK.T))
 logmel=tf.transpose((10.0/np.log(10.0))*tf.math.log(tf.maximum(mel,1e-12)))
 mfcc=tf.linalg.matmul(tf.constant(DCT_BASIS),logmel);delta=gradient(mfcc);delta2=gradient(delta)
 rms=tf.sqrt(tf.reduce_mean(windowed*windowed,axis=1)+1e-12)
 rmsdb=(20.0/np.log(10.0))*tf.math.log(tf.maximum(rms,1e-6))
 scalar=tf.stack([tf.reduce_mean(rmsdb),tf.math.reduce_std(rmsdb),tf.reduce_max(tf.abs(x)),tf.reduce_mean(tf.abs(x)),tf.reduce_mean(tf.cast(x[:-1]*x[1:]<0,tf.float32))])
 return tf.concat([statistics(logmel),statistics(mfcc),statistics(delta),statistics(delta2),scalar],axis=0)
