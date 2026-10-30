package com.example
import com.example.ml.ModelAudioPreprocessor
import org.junit.Assert.*
import org.junit.Test
class FullEventCoverageTest {
 @Test fun earlySpikeMustNotHideLateRepeatedTaps() {
  val x=FloatArray(240000);x[100]=1f
  for(i in 140000..200000 step 8000)x[i]=.2f
  val w=ModelAudioPreprocessor.windows(x)
  assertEquals(0,w.first().startSample)
  assertEquals(x.size-64000,w.last().startSample)
  val covered=BooleanArray(x.size)
  w.forEach { z -> for(i in z.startSample until minOf(x.size,z.startSample+64000))covered[i]=true }
  assertTrue("Every actual sample including tail must be analyzed",covered.all { it })
  assertTrue(w.size<=7)
 }
 @Test fun shortAudioIsPaddedAndLongWindowGapsAtMostTwoSeconds() {
  assertEquals(1,ModelAudioPreprocessor.windows(floatArrayOf(.1f)).size)
  val w=ModelAudioPreprocessor.windows(FloatArray(129151))
  assertTrue(w.size>1);w.zipWithNext().forEach { (a,b)->assertTrue(b.startSample-a.startSample<=32000) }
  assertEquals(65151,w.last().startSample)
 }
}
