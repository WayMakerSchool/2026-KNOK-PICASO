package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ml.AudioClassifier
import com.example.ml.offlineAnalysisJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Numerical portability on a declared cohort; Android reruns are not extra independent samples. */
@RunWith(AndroidJUnit4::class)
class OvernightModelParityDeviceTest {
    @Test fun storedPhysicalWavsMatchPythonOnActualAndroid() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val expected=JSONObject(inst.context.assets.open("overnight_parity_expected.json").bufferedReader().use{it.readText()})
        val results=JSONArray()
        AudioClassifier(context,useV2=true).use { model ->
            assertEquals(expected.getString("model_version"),model.modelVersion)
            val cases=expected.getJSONArray("cases")
            for(i in 0 until cases.length()) {
                val row=cases.getJSONObject(i)
                val file=File(context.filesDir,"night-probes/${row.getString("file")}")
                val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                assertEquals(row.getString("wav_sha256"),hash)
                val reference=row.getJSONObject("reference")
                val actual=model.classify(file)
                assertEquals(reference.getString("label"),actual.label)
                assertEquals(reference.getInt("window_start_sample"),actual.windowStartSample)
                assertEquals(listOf(reference.getInt("gate_window_start_sample")),actual.auxiliaryWindowStarts)
                val probability=reference.getJSONObject("probabilities")
                for((label,value)in actual.probabilities)assertEquals(label,probability.getDouble(label),value.toDouble(),1e-4)
                results.put(JSONObject().put("file",file.name).put("wav_sha256",hash).put("analysis",JSONObject(actual.offlineAnalysisJson())))
            }
        }
        File(context.filesDir,"overnight-model-portability.json").writeText(JSONObject()
            .put("scope",expected.optString("scope", "Actual Android numerical portability on stored fitting-related physical WAVs; not independent classification accuracy"))
            .put("cases",results).toString(2))
    }
}
