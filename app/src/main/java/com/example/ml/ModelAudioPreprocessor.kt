package com.example.ml

import java.io.File

data class ModelAudioWindow(val samples: FloatArray, val startSample: Int, val cropped: Boolean)

/** Same label-blind 100 ms energy bins and four-second crop as the Python recipe. */
object ModelAudioPreprocessor {
    const val WINDOW_SAMPLES = 64000
    /** PCM16 resolution guard only; this is not a calibrated ambient-noise gate. */
    fun belowQuantizationFloor(samples: FloatArray,peakLsb: Double,rmsLsb: Double): Boolean {
        if(peakLsb<=0.0 || rmsLsb<=0.0)return false
        val dc=samples.sumOf { it.toDouble() }/samples.size
        var peak=0.0;var square=0.0
        samples.forEach { val v=it-dc;peak=maxOf(peak,kotlin.math.abs(v));square+=v*v }
        return peak*32768.0<=peakLsb || kotlin.math.sqrt(square/samples.size)*32768.0<=rmsLsb
    }
    /** Cover the entire event with a maximum two-second hop; do not pick one loud spike. */
    fun windows(samples: FloatArray): List<ModelAudioWindow> {
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "분석할 PCM 음원이 비었거나 손상됐습니다." }
        val last = maxOf(0, samples.size - WINDOW_SAMPLES)
        val count = (last + 31999) / 32000 + 1
        return (0 until count).map { index ->
            val start = if (count == 1) 0 else (last.toLong() * index / (count - 1)).toInt()
            val result = FloatArray(WINDOW_SAMPLES)
            samples.copyInto(result, endIndex = minOf(samples.size, start + WINDOW_SAMPLES), startIndex = start)
            ModelAudioWindow(result, start, samples.size > WINDOW_SAMPLES)
        }
    }
    fun windowsForPolicy(samples: FloatArray, policy: String): List<ModelAudioWindow> = when(policy) {
        "maximum_integrated_100ms_rms_4seconds" -> listOf(activityWindow(samples))
        "maximum_100ms_rms_centered_4seconds_clamped" -> listOf(window(samples))
        else -> windows(samples)
    }
    /** Same label-blind four-second integrated RMS selector as the training code. */
    fun activityWindow(samples: FloatArray): ModelAudioWindow {
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "분석할 PCM 음원이 비었거나 손상됐습니다." }
        val last=maxOf(0,samples.size-WINDOW_SAMPLES)
        var start=0
        if(last>0) {
            val dc=samples.sumOf { it.toDouble() }/samples.size
            val bins=samples.size/1600
            val envelope=DoubleArray(bins) { bin ->
                var sum=0.0
                for(i in bin*1600 until (bin+1)*1600) { val v=samples[i]-dc;sum+=v*v }
                kotlin.math.sqrt(sum/1600)
            }
            var score=0.0
            for(i in 0 until 40)score+=envelope[i]
            var best=score
            for(bin in 1..(bins-40)) {
                score+=envelope[bin+39]-envelope[bin-1]
                if(score>best) { best=score;start=bin*1600 }
            }
            if(last%1600!=0) {
                var tail=0.0
                for(bin in 0 until 40) {
                    var sum=0.0
                    for(i in last+bin*1600 until last+(bin+1)*1600) { val v=samples[i]-dc;sum+=v*v }
                    tail+=kotlin.math.sqrt(sum/1600)
                }
                if(tail>best+1e-12)start=last
            }
        }
        val result=FloatArray(WINDOW_SAMPLES)
        samples.copyInto(result,endIndex=minOf(samples.size,start+WINDOW_SAMPLES),startIndex=start)
        return ModelAudioWindow(result,start,samples.size>WINDOW_SAMPLES)
    }
    fun read(file: File): ModelAudioWindow = window(AudioFeatureExtractor.readPcm16Wav(file))

    fun window(samples: FloatArray): ModelAudioWindow {
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "분석할 PCM 음원이 비었거나 손상됐습니다." }
        var bestEnergy = -1.0
        var bestBin = 0
        for (bin in 0 until (samples.size + 1599) / 1600) {
            var energy = 0.0
            for (i in bin * 1600 until minOf(samples.size, (bin + 1) * 1600)) {
                energy += samples[i].toDouble() * samples[i]
            }
            if (energy > bestEnergy) { bestEnergy = energy; bestBin = bin }
        }
        val center = bestBin * 1600 + 800
        val start = (center - 32000).coerceIn(0, maxOf(0, samples.size - WINDOW_SAMPLES))
        val result = FloatArray(WINDOW_SAMPLES)
        samples.copyInto(result, endIndex = minOf(samples.size, start + WINDOW_SAMPLES), startIndex = start)
        // DC removal and gain normalization are inside the exported raw model.
        return ModelAudioWindow(result, start, samples.size > WINDOW_SAMPLES)
    }
}
