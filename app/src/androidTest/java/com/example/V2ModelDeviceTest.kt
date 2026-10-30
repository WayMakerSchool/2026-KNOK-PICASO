package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ml.AudioClassifier
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.data.AppDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@RunWith(AndroidJUnit4::class)
class V2ModelDeviceTest {
    @Test fun finalWavDirectoryFsyncWorksOnActualAndroidFilesystem() {
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"directory-fsync-test").apply{mkdirs()}
        val temporary=File(folder,"probe.part");val final=File(folder,"probe.wav")
        java.io.FileOutputStream(temporary).use { it.write(byteArrayOf(1,2,3));it.fd.sync() }
        assertTrue(temporary.renameTo(final))
        com.example.data.EspWavFiles.syncDirectory(folder)
        assertArrayEquals(byteArrayOf(1,2,3),final.readBytes());final.delete();folder.delete()
    }
    @Test fun quantizationLimitedPcmIsHeldWithoutAModelScore() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"one-lsb-v2.wav")
        val b=ByteBuffer.allocate(44+128000).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray());b.putInt(36+128000);b.put("WAVEfmt ".toByteArray())
        b.putInt(16);b.putShort(1);b.putShort(1);b.putInt(16000);b.putInt(32000)
        b.putShort(2);b.putShort(16);b.put("data".toByteArray());b.putInt(128000)
        repeat(64000){b.putShort(if(it%2==0)1.toShort() else (-1).toShort())};file.writeBytes(b.array())
        AudioClassifier(context,useV2=true).use {
            val result=it.classify(file)
            assertEquals("normal_or_unknown",result.label);assertEquals("quantization_limited",result.signalQuality)
            assertTrue(result.probabilities.isEmpty());assertEquals(0f,result.confidence,0f)
        }
        file.delete()
    }
    @Test fun acknowledgementFollowsDatabaseFullSynchronousCommit() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.IO) {
            val db=AppDatabase.getDatabase(context)
            db.withTransaction {
                db.openHelper.writableDatabase.query("PRAGMA synchronous").use {
                    assertTrue(it.moveToFirst())
                    android.util.Log.i("KNOK_V2_DURABILITY","primary synchronous=${it.getInt(0)}")
                    assertEquals("ACK needs a FULL synchronous database commit",2,it.getInt(0))
                }
            }
        }
    }
    @Test fun actualAndroidRuntimeMatchesPythonForPhysicalMicrophoneWav() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val file = File(inst.targetContext.cacheDir, "physical-parity.wav")
        inst.context.assets.open("physical_probe.wav").use { input -> file.outputStream().use(input::copyTo) }
        val expected = JSONObject(inst.context.assets.open("physical_probe_expected.json").bufferedReader().use { it.readText() })
        AudioClassifier(inst.targetContext, useV2 = true).use { classifier ->
            val actual = classifier.classify(file)
            assertEquals(expected.getInt("window_start_sample"), actual.windowStartSample)
            actual.probabilities.forEach { (label, value) ->
                assertEquals(label, expected.getJSONObject("probabilities").getDouble(label), value.toDouble(), 1e-4)
            }
            assertEquals(JSONObject(inst.targetContext.assets.open("knok_v2_model_manifest.json").bufferedReader().use { it.readText() }).getString("model_version"),actual.modelVersion)
            android.util.Log.i("KNOK_V2_PARITY", "inferenceMs=${actual.inferenceMs} label=${actual.label} probabilities=${actual.probabilities}")
        }
        file.delete()
    }

    @Test fun silenceIsUnknownAndMalformedWavIsAnError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir,"silent-v2.wav")
        val b = ByteBuffer.allocate(44+128000).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray());b.putInt(36+128000);b.put("WAVEfmt ".toByteArray())
        b.putInt(16);b.putShort(1);b.putShort(1);b.putInt(16000);b.putInt(32000)
        b.putShort(2);b.putShort(16);b.put("data".toByteArray());b.putInt(128000)
        file.writeBytes(b.array())
        AudioClassifier(context,useV2=true).use { classifier ->
            assertEquals("normal_or_unknown",classifier.classify(file).label)
            file.writeText("damaged wav")
            assertThrows(Exception::class.java) { classifier.classify(file) }
        }
        file.delete()
    }
}
