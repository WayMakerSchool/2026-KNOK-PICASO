package com.example

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelCompatibilityTest {
    @Test
    fun deployedModelMatchesFeatureExtractorAndLabels() {
        val workingDir = File(requireNotNull(System.getProperty("user.dir")))
        val assetsDir = listOf(
            File(workingDir, "app/src/main/assets"),
            File(workingDir, "src/main/assets")
        ).firstOrNull(File::isDirectory) ?: error("Android assets 폴더를 찾을 수 없습니다.")
        val modelFile = File(
            assetsDir,
            "knok_interfloor_v1.tflite"
        )
        val labelsFile = File(
            assetsDir,
            "knok_interfloor_v1_labels.txt"
        )

        assertTrue("배포 모델 파일이 없습니다.", modelFile.isFile)
        assertTrue("배포 라벨 파일이 없습니다.", labelsFile.isFile)
        val labels = labelsFile.readLines().map(String::trim).filter(String::isNotEmpty)
        val modelBytes = modelFile.readBytes()

        // FlatBuffer files store their four-byte identifier at bytes 4..7.
        // The Android TFLite runtime itself is not loadable in a Windows JVM
        // unit test, so tensor shapes are verified by the Python export step.
        assertTrue("TFLite 모델이 비어 있습니다.", modelBytes.size > 1_024)
        assertEquals("TFL3", modelBytes.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        assertEquals(
            listOf(
                "dragging_furniture",
                "footstep",
                "hammering",
                "instant_impact",
                "normal_or_unknown",
                "vacuum_cleaner"
            ),
            labels
        )
    }
}
