package com.example.ml

import kotlin.math.abs

/** Fixed probability fusion; it neither learns labels nor changes the .65 decision threshold. */
object PairGateFusion {
    private val other = intArrayOf(0,3,4,5)
    fun combine(global: FloatArray, gate: FloatArray): FloatArray {
        require(global.size==6 && gate.size==6)
        require(listOf(global,gate).all { p -> p.all { it.isFinite() && it>=0f } && abs(p.sum()-1f)<1e-3f })
        val output=FloatArray(6)
        output[1]=gate[1];output[2]=gate[2]
        val mass=(1.0-gate[1].toDouble()-gate[2].toDouble()).coerceAtLeast(0.0)
        val denominator=other.sumOf { global[it].toDouble() }
        for(index in other)output[index]=(mass*(if(denominator>1e-20)global[index]/denominator else .25)).toFloat()
        return output
    }
}
