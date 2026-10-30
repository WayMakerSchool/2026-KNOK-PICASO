package com.example.ml

import java.io.Closeable
import java.io.File

interface SoundClassifier : Closeable {
    val modelVersion: String
    fun classify(wavFile: File): AudioClassificationResult
}
