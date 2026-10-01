package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.auth.AccountSession
import com.example.auth.FirebaseClientProvider
import com.example.data.AppDatabase
import com.example.ml.SoundAnalysis
import com.example.ui.NoiseViewModel
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: uses one existing recording and may make one paid Gemini call. */
@RunWith(AndroidJUnit4::class)
class SoundAnalysisDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun registeredPhoneAnalyzesExistingRecordingAndPersistsResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPaidSoundAnalysis") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseClientProvider.appOrNull(context) ?: error("Firebase is not configured")
        val uid = FirebaseClientProvider.authOrNull(context)?.currentUser?.uid ?: error("Sign in on the phone first")
        runBlocking {
            val token = withTimeout(30_000) { FirebaseAppCheck.getInstance(app).getAppCheckToken(true).await() }
            assertTrue("App Check must issue a real JWT", token.token.split('.').size == 3)
        }
        compose.waitUntil(20_000) { AccountSession.userId.value == uid }
        val dao = AppDatabase.getDatabase(context).noiseDao()
        val record = runBlocking {
            dao.getRecordsForUserOnce(uid).firstOrNull {
                it.soundAnalysisJson == null && it.recordingPath?.let { path -> File(path).isFile } == true
            } ?: error("No pending recording exists for the signed-in user")
        }
        compose.onNodeWithText("녹음목록").performClick()
        val tag = "sound-analysis-${record.id}"
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo()
        compose.onNode(hasText("소리 분석") and hasAnyAncestor(hasTestTag(tag))).performClick()
        val viewModel = ViewModelProvider(compose.activity)[NoiseViewModel::class.java]
        compose.waitUntil(150_000) {
            viewModel.analysisErrors.value[record.id] != null || runBlocking { dao.findById(record.id)?.soundAnalysisJson != null }
        }
        assertNull("Analysis failed: ${viewModel.analysisErrors.value[record.id]}", viewModel.analysisErrors.value[record.id])
        val saved = runBlocking { dao.findById(record.id) } ?: error("Existing record disappeared")
        val analysis = SoundAnalysis.fromJson(saved.soundAnalysisJson ?: error("Analysis was not saved"))
        assertEquals(record.recordingPath, saved.recordingPath)
        assertEquals(uid, saved.userId)
        assertTrue(analysis.model in setOf("gemini-3.5-flash-lite", "local-silence-check"))
        compose.onNode(hasText("추정 소리: ${analysis.sound}") and hasAnyAncestor(hasTestTag(tag))).assertExists()
        runBlocking {
            withTimeout(60_000) {
                while (dao.findById(record.id)?.syncState != "SYNCED") delay(500)
            }
            val cloud = FirebaseClientProvider.firestoreOrNull(context)!!.collection("users").document(uid)
                .collection("noise_records").document(saved.syncId!!).get(Source.SERVER).await()
            assertEquals(saved.soundAnalysisJson, cloud.getString("sound_analysis_json"))
        }
        val output = context.getExternalFilesDir("verification") ?: error("No verification output folder")
        File(output, "sound-analysis-result.json").writeText(JSONObject()
            .put("recordId", record.id).put("model", analysis.model).put("sound", analysis.sound)
            .put("certainty", analysis.certainty).put("roomSaved", true).put("firestoreSaved", true)
            .put("appCheckVerified", true).toString())
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(output, "sound-analysis-device.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
