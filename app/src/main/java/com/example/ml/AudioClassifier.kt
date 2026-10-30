package com.example.ml

import android.content.Context
import com.example.BuildConfig
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class AudioClassificationResult(
    val label: String,
    val confidence: Float,
    val isInterfloorCandidate: Boolean,
    val probabilities: Map<String, Float>,
    val modelVersion: String = "knok-interfloor-v1",
    val inferenceMs: Long = 0,
    val windowStartSample: Int = 0,
    val windowCropped: Boolean = false,
    val signalQuality: String = "normal",
    val windowStarts: List<Int> = emptyList(),
    val auxiliaryWindowStarts: List<Int> = emptyList(),
    val preprocessingPolicy: String = ""
)

/** Runs the local, offline audio classifier shipped with the app. */
class AudioClassifier(context: Context, private val useV2: Boolean = BuildConfig.KNOK_V2) : SoundClassifier {
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

    private val manifest = if (useV2) JSONObject(context.assets.open("knok_v2_model_manifest.json").bufferedReader().use { it.readText() }) else null
    override val modelVersion = manifest?.getString("model_version") ?: MODEL_VERSION
    private val labels = context.assets.open(if (useV2) "knok_v2_labels.txt" else LABELS_ASSET).bufferedReader().use { reader ->
        reader.readLines().map(String::trim).filter(String::isNotEmpty)
    }

    @Suppress("DEPRECATION")
    private val interpreter = Interpreter(
        loadModel(context),
        Interpreter.Options().setNumThreads(2)
    )
    private val gateSpec = manifest?.optJSONObject("auxiliary_gate")
    private val gateInterpreter = gateSpec?.let { spec ->
        Interpreter(loadCheckedAsset(context,spec.getString("asset_file"),spec.getString("model_sha256")),
            Interpreter.Options().setNumThreads(2))
    }

    init {
        require(labels.isNotEmpty()) { "분류 라벨이 비어 있습니다." }
        val input = interpreter.getInputTensor(0)
        val expected = if (useV2) intArrayOf(64000) else intArrayOf(1, AudioFeatureExtractor.FEATURE_COUNT)
        require(input.shape().contentEquals(expected)) {
            "TFLite 입력 크기가 다릅니다: ${input.shape().contentToString()}"
        }
        require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, labels.size))) { "모델 출력과 라벨 순서 확인이 필요합니다." }
        manifest?.let { value ->
            val names = value.getJSONArray("labels")
            require(labels == (0 until names.length()).map(names::getString)) { "모델 라벨 순서가 다릅니다." }
        }
        if(manifest?.optString("inference_pipeline")=="quiet_context_pair_gate_v1") {
            val gate=requireNotNull(gateInterpreter) { "보조 분류 모델이 없습니다." }
            require(gate.getInputTensor(0).shape().contentEquals(intArrayOf(64000)))
            require(gate.getOutputTensor(0).shape().contentEquals(intArrayOf(1,labels.size)))
            val names=requireNotNull(gateSpec).getJSONArray("output_labels")
            require(labels==(0 until names.length()).map(names::getString))
        } else require(gateInterpreter==null) { "지원하지 않는 모델 구성이에요." }
    }

    @Synchronized
    override fun classify(wavFile: File): AudioClassificationResult {
        val started = System.nanoTime()
        val rawSamples = if(useV2) AudioFeatureExtractor.readPcm16Wav(wavFile) else null
        val windows = rawSamples?.let { ModelAudioPreprocessor.windowsForPolicy(it, manifest?.optString("crop", "") ?: "") }
        val window = windows?.firstOrNull()
        val floor=manifest?.optJSONObject("quantization_floor")
        if(windows!=null && floor!=null && windows.all { ModelAudioPreprocessor.belowQuantizationFloor(
                it.samples,floor.getDouble("peak_pcm16_lsb"),floor.getDouble("rms_pcm16_lsb")) }) {
            return AudioClassificationResult(UNKNOWN_LABEL,0f,false,emptyMap(),modelVersion,
                (System.nanoTime()-started)/1_000_000,window?.startSample ?: 0,window?.cropped ?: false,"quantization_limited")
        }
        val output = Array(1) { FloatArray(labels.size) }
        if (windows != null) {
            for (part in windows) {
                val partOutput = Array(1) { FloatArray(labels.size) }
                interpreter.run(part.samples, partOutput)
                require(partOutput[0].all { it.isFinite() && it >= 0f })
                for (index in labels.indices) output[0][index] += partOutput[0][index] / windows.size
            }
        } else interpreter.run(arrayOf(AudioFeatureExtractor.extract(wavFile)), output)

        var auxiliaryStarts=emptyList<Int>()
        val probabilities = gateInterpreter?.let { gate ->
            val spec=requireNotNull(gateSpec)
            val denoised=ModelSpectralDenoiser.process(requireNotNull(rawSamples),spec.getDouble("denoise_alpha"))
            val gateWindows=ModelAudioPreprocessor.windowsForPolicy(denoised,spec.getString("crop"))
            auxiliaryStarts=gateWindows.map { it.startSample }
            val gateProbability=FloatArray(labels.size)
            for(part in gateWindows) {
                val result=Array(1) { FloatArray(labels.size) }
                gate.run(part.samples,result)
                for(index in labels.indices)gateProbability[index]+=result[0][index]/gateWindows.size
            }
            PairGateFusion.combine(output[0],gateProbability)
        } ?: output[0]
        require(probabilities.all { it.isFinite() && it >= 0f } && probabilities.sum() > 0f) { "모델이 유효한 분류 결과를 반환하지 않았습니다." }
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
            probabilities = namedProbabilities,
            modelVersion = modelVersion,
            inferenceMs = (System.nanoTime() - started) / 1_000_000,
            windowStartSample = window?.startSample ?: 0,
            windowCropped = window?.cropped ?: false,
            windowStarts = windows?.map { it.startSample } ?: emptyList(),
            auxiliaryWindowStarts = auxiliaryStarts,
            preprocessingPolicy = manifest?.optString("inference_pipeline", "") ?: ""
        )
    }

    @Synchronized
    override fun close() {
        interpreter.close()
        gateInterpreter?.close()
    }

    private fun loadModel(context: Context): ByteBuffer {
        return loadCheckedAsset(context,if(useV2)"knok_v2_yamnet.tflite" else MODEL_ASSET,manifest?.getString("model_sha256"))
    }

    private fun loadCheckedAsset(context: Context,asset: String,expectedHash: String?): ByteBuffer {
        val bytes=context.assets.open(asset).use { it.readBytes() }
        expectedHash?.let {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
            require(digest == it) { "분류 모델 파일 무결성 확인에 실패했습니다." }
        }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }
}
