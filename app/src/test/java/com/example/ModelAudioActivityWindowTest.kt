package com.example
import com.example.ml.ModelAudioPreprocessor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
class ModelAudioActivityWindowTest {
 @Test fun continuousActivityWinsOverIsolatedSpike() {
  val x=FloatArray(128000);x[8000]=1f
  for(i in 64000 until x.size)x[i]=(0.1*sin(2*Math.PI*220*(i-64000)/16000)).toFloat()
  val w=ModelAudioPreprocessor.activityWindow(x)
  assertEquals(64000,w.startSample);assertTrue(w.cropped)
 }
 @Test fun nonAlignedTailIsConsidered() {
  val x=FloatArray(128123);val start=x.size-64000
  for(i in start until x.size)x[i]=(0.1*sin(2*Math.PI*220*(i-start)/16000)).toFloat()
  assertEquals(start,ModelAudioPreprocessor.activityWindow(x).startSample)
 }
 @Test fun shortInputIsPaddedWithoutChangingRawSignal() {
  val x=FloatArray(32000){0.2f};val w=ModelAudioPreprocessor.activityWindow(x)
  assertEquals(0,w.startSample);assertEquals(64000,w.samples.size);assertFalse(w.cropped)
  assertEquals(.2f,w.samples[100],0f);assertEquals(0f,w.samples[50000],0f)
 }
 @Test fun explicitPolicySelectsExactlyOneActivityWindow() {
  assertEquals(1,ModelAudioPreprocessor.windowsForPolicy(FloatArray(128000){0.1f},"maximum_integrated_100ms_rms_4seconds").size)
  assertEquals(3,ModelAudioPreprocessor.windowsForPolicy(FloatArray(128000){0.1f},"all_evenly_spaced_overlapping4s_max2s_hop_equal_mean").size)
 }
}
