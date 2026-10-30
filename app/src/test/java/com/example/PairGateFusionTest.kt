package com.example

import com.example.ml.PairGateFusion
import org.junit.Assert.*
import org.junit.Test

class PairGateFusionTest {
    @Test fun keepsFootHammerGateAndConditionallyDistributesFourOthers() {
        val global=floatArrayOf(.2f,.3f,.1f,.1f,.1f,.2f)
        val gate=floatArrayOf(.1f,.6f,.2f,.04f,.02f,.04f)
        val actual=PairGateFusion.combine(global,gate)
        assertArrayEquals(floatArrayOf(1f/15,.6f,.2f,1f/30,1f/30,1f/15),actual,1e-6f)
        assertEquals(1f,actual.sum(),1e-6f)
    }
    @Test fun emptyConditionalHasFiniteUniformFallback() {
        val actual=PairGateFusion.combine(floatArrayOf(0f,.7f,.3f,0f,0f,0f),floatArrayOf(.1f,.2f,.3f,.1f,.1f,.2f))
        assertArrayEquals(floatArrayOf(.125f,.2f,.3f,.125f,.125f,.125f),actual,1e-6f)
        assertTrue(actual.all { it.isFinite() && it>=0f })
    }
    @Test fun rejectsInvalidProbabilitiesInsteadOfHidingAnError() {
        assertThrows(IllegalArgumentException::class.java) { PairGateFusion.combine(FloatArray(5),FloatArray(6)) }
        assertThrows(IllegalArgumentException::class.java) { PairGateFusion.combine(FloatArray(6),floatArrayOf(0f,2f,-1f,0f,0f,0f)) }
    }
}
