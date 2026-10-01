package com.example.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioClassificationResult(
    val label: String,
    val confidence: Float,
    val isInterfloorCandidate: Boolean,
    val probabilities: Map<String, Float>
)

/** Runs the local, offline audio classifier shipped with the app. */
class AudioClassifier(context: Context) : Closeable {
    companion object {
        const val MODEL_VERSION = "knok-interfloor-v1"
        private const val MODEL_ASSET = "knok_interfloor_v1.tflite"
        private const val LABELS_ASSET = "knok_interfloor_v1_labels.txt"
        private const val UNKNOWN_LABEL = "normal_or_unknown"
        private const val UNKNOWN_THRESHOLD = 0.65f
        private val INTERFLOOR_LABELS = setOf(
            "footstep",
            "instant_impact",
            "dragging_furniture",
            "hammering"
        )
    }

    private val labels = context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
        reader.readLines().map(String::trim).filter(String::isNotEmpty)
    }

    @Suppress("DEPRECATION")
    private val interpreter = Interpreter(
        loadModel(context),
        Interpreter.Options().setNumThreads(2)
    )

    init {
        require(labels.isNotEmpty()) { "분류 라벨이 비어 있습니다." }
        val input = interpreter.getInputTensor(0)
        require(input.shape().contentEquals(intArrayOf(1, AudioFeatureExtractor.FEATURE_COUNT))) {
            "TFLite 입력 크기가 다릅니다: ${input.shape().contentToString()}"
        }
    }

    @Synchronized
    fun classify(wavFile: File): AudioClassificationResult {
        val features = AudioFeatureExtractor.extract(wavFile)
        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(arrayOf(features), output)

        val probabilities = output[0]
        var bestIndex = 0
        for (index in 1 until probabilities.size) {
            if (probabilities[index] > probabilities[bestIndex]) bestIndex = index
        }
        val confidence = probabilities[bestIndex].coerceIn(0.0f, 1.0f)
        val rawLabel = labels.getOrElse(bestIndex) { UNKNOWN_LABEL }
        val label = if (rawLabel == UNKNOWN_LABEL || confidence < UNKNOWN_THRESHOLD) {
            UNKNOWN_LABEL
        } else {
            rawLabel
        }
        val namedProbabilities = labels.mapIndexed { index, name ->
            name to probabilities.getOrElse(index) { 0.0f }
        }.toMap()
        return AudioClassificationResult(
            label = label,
            confidence = confidence,
            isInterfloorCandidate = label in INTERFLOOR_LABELS,
            probabilities = namedProbabilities
        )
    }

    override fun close() {
        interpreter.close()
    }

    private fun loadModel(context: Context): ByteBuffer {
        // Read the small model into a direct buffer so this also works when an
        // Android build compresses the asset; openFd() only supports stored
        // (uncompressed) assets.
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }
}
