package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ml.AudioClassifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Development only. Input names are event IDs; no ground truth reaches either model. */
@RunWith(AndroidJUnit4::class)
class V2ReplayComparisonDeviceTest {
    @Test fun compareBothModelsOnCapturedPcmWithoutSourceLabels() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.filesDir,"v2-probes")
        val files=input.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("Push private physical replay WAVs to v2-probes first",files.isNotEmpty())
        val rows=JSONArray()
        AudioClassifier(context,useV2=false).use { baseline ->
            AudioClassifier(context,useV2=true).use { candidate ->
                for(file in files) {
                    val row=JSONObject().put("wav_file",file.name).put("scope","speaker_replay_development")
                    val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                    row.put("wav_sha256",hash)
                    for((name,model) in listOf("baseline" to baseline,"candidate" to candidate)) {
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
        File(context.filesDir,"v2-replay-comparison.json").writeText(rows.toString(2))
        android.util.Log.i("KNOK_V2_COMPARISON","actual Android compared ${files.size} physical WAVs")
    }
}
