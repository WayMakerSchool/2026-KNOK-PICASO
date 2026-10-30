package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ml.AudioClassifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** V5 in test assets and V6 in target assets; event IDs are the only input names. */
@RunWith(AndroidJUnit4::class)
class V6ReplayDeviceTest {
    @Test fun compareFrozenModelsOnIdenticalPhysicalWavsWithoutGroundTruth() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val files=File(inst.targetContext.filesDir,"v6-probes").listFiles { f -> f.extension=="wav" }?.sortedBy { it.name }.orEmpty()
        assertTrue("Push frozen physical WAVs first",files.isNotEmpty())
        val rows=JSONArray()
        AudioClassifier(inst.context,useV2=true).use { stable ->
            AudioClassifier(inst.targetContext,useV2=true).use { candidate ->
                assertTrue(stable.modelVersion.endsWith("v5-signal-guard"))
                assertTrue(candidate.modelVersion.endsWith("microphone-v6"))
                for(file in files) {
                    val row=JSONObject().put("wav_file",file.name)
                    val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                    row.put("wav_sha256",hash)
                    for((name,model) in listOf("stable_v5" to stable,"candidate_v6" to candidate)) {
                        val result=model.classify(file)
                        assertTrue(result.confidence.isFinite())
                        row.put(name,JSONObject().put("label",result.label).put("model_score",result.confidence.toDouble())
                            .put("model_version",result.modelVersion).put("probabilities",JSONObject(result.probabilities))
                            .put("inference_ms",result.inferenceMs).put("window_start_sample",result.windowStartSample))
                    }
                    rows.put(row)
                }
            }
        }
        File(inst.targetContext.filesDir,"v6-model-comparison.json").writeText(rows.toString(2))
        android.util.Log.i("KNOK_V6_COMPARISON","Compared ${files.size} physical WAVs on actual Android")
    }
}
