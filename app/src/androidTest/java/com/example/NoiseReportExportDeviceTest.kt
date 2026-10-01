package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.report.NoiseReportSharing
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No microphone, login, network or paid API call; confirms actual Android FileProvider I/O. */
@RunWith(AndroidJUnit4::class)
class NoiseReportExportDeviceTest {
    @Test fun sharedReportCanBeReadAndPrivateFilesAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "noise-reports/device-check.pdf").apply { parentFile!!.mkdirs(); writeText("report-test") }
        try {
            val intent = NoiseReportSharing.intent(context, file)
            val uri = intent.clipData!!.getItemAt(0).uri
            assertEquals("report-test", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertTrue(runCatching { NoiseReportSharing.intent(context, File(context.filesDir, "private-file")) }.isFailure)
        } finally { file.delete() }
    }
}
