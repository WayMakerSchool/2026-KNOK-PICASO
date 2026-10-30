package com.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AppDatabase
import com.example.data.EspEventImporter
import com.example.data.EspRecorderClient
import com.example.data.NoiseRepository
import com.example.ml.AudioClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Imports already captured real device events; never starts detection or a new recording. */
@RunWith(AndroidJUnit4::class)
class QuietPendingDeviceTest {
    @Test fun actualPendingDeviceAudioIsImportedAnalyzedAndPersistedWithoutStartingCapture(): Unit = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val expected = JSONObject(inst.context.assets.open("quiet_pending_expected.json").bufferedReader().use { it.readText() })
        val base = expected.getString("base_url")
        val version = expected.getString("model_version")
        val events = expected.getJSONArray("events")
        val wanted = (0 until events.length()).map { events.getJSONObject(it).getString("id") }.toSet()
        AudioClassifier(context, useV2 = true).use { assertEquals(version, it.modelVersion) }
        val client = EspRecorderClient()
        val before = client.getStatus(base)
        assertEquals(expected.getString("device_id"), before.deviceId)
        val captureBoot=expected.getString("boot_id")
        assertTrue(before.ready)
        assertFalse("Quiet integration must keep automatic detection off", before.autoEnabled)
        assertFalse("Quiet integration must not begin a recording", before.recording)
        val repository = NoiseRepository(AppDatabase.getDatabase(context).noiseDao())
        val pending = client.listEvents(base).map { it.id }.toSet()
        assertTrue("Preserve unrelated queued events", wanted.containsAll(pending))
        for(id in wanted) {
            assertTrue("Original captured event ID must retain its capture boot",id.startsWith(before.deviceId+"-"+captureBoot+"-"))
            val existing=withContext(Dispatchers.IO) { repository.findDeviceEvent(id) }
            assertTrue("Buffered capture unavailable; do not fabricate a replacement",id in pending || existing!=null)
        }
        val imported = withContext(Dispatchers.IO) {
            EspEventImporter(client, repository, context.filesDir).importPending(base, null)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Missing launcher activity")
        context.getSharedPreferences("esp32_recorder",0).edit().putString("base_url",base)
            .putFloat("detection_threshold",85f).commit()
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.nanoTime() + 180_000_000_000L
        while (true) {
            val unfinished = withContext(Dispatchers.IO) {
                wanted.any { repository.findDeviceEvent(it)?.deviceClassificationPending != false }
            }
            if (!unfinished) break
            assertTrue("App must finish its asynchronous inference worker", System.nanoTime() < deadline)
            delay(300)
        }
        val rows = JSONArray()
        for (i in 0 until events.length()) {
            val fixture = events.getJSONObject(i)
            val record = withContext(Dispatchers.IO) { repository.findDeviceEvent(fixture.getString("id")) }
                ?: error("Device recording was not persisted")
            val audio = File(requireNotNull(record.recordingPath))
            val hash = MessageDigest.getInstance("SHA-256").digest(audio.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("Durable Android WAV must equal verified host capture", fixture.getString("wav_sha256"), hash)
            assertFalse(record.deviceClassificationPending)
            assertNotEquals("analysis_failed", record.classificationLabel)
            val analysis = JSONObject(requireNotNull(record.soundAnalysisJson))
            assertEquals(version, analysis.getString("model"))
            val actualProb = analysis.getJSONObject("probabilities")
            val referenceProb = fixture.getJSONObject("reference_probabilities")
            val keys = referenceProb.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                assertEquals("Python/Android probability parity: $key", referenceProb.getDouble(key), actualProb.getDouble(key), 1e-4)
            }
            assertEquals("Prediction parity is separate from ground-truth accuracy", fixture.getString("reference_model_label"), record.classificationLabel)
            rows.put(JSONObject().put("device_event_id", record.deviceEventId).put("record_id", record.id)
                .put("wav_sha256", hash).put("label", record.classificationLabel)
                .put("model_score", record.classificationConfidence).put("analysis", analysis)
                .put("received_timestamp_ms", record.timestamp))
        }
        assertEquals(0, client.listEvents(base).size)
        assertEquals("Import is idempotent", 0, EspEventImporter(client, repository, context.filesDir).importPending(base, null))
        val after = client.getStatus(base)
        assertFalse(after.autoEnabled); assertFalse(after.recording)
        assertEquals(before.bootId, after.bootId)
        val report = JSONObject().put("scope", "Actual Android import of previously captured physical WAVs; no new playback or recording")
            .put("prediction_parity_is_not_ground_truth_accuracy", true).put("newly_imported", imported)
            .put("capture_boot_id",captureBoot).put("import_boot_id",before.bootId)
            .put("model_version", version).put("records", rows).put("auto_off", true).put("recording_off", true)
        File(context.filesDir, "quiet-pending-integration.json").writeText(report.toString(2))
        android.util.Log.i("KNOK_QUIET_INTEGRATION", "Imported, analyzed and durably saved ${rows.length()} real pending device recordings")
    }
}
