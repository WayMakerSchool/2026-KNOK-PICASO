package com.example

import com.example.ml.AudioFeatureExtractor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmSignedDecodeTest {
    @Test fun signed16BitSamplesAreDecodedOnceWithoutDoubleSignExtension() {
        val values = shortArrayOf(Short.MIN_VALUE,-1234,-1,0,1,1234,Short.MAX_VALUE)
        val b=ByteBuffer.allocate(44+values.size*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray());b.putInt(36+values.size*2);b.put("WAVEfmt ".toByteArray())
        b.putInt(16);b.putShort(1);b.putShort(1);b.putInt(16000);b.putInt(32000)
        b.putShort(2);b.putShort(16);b.put("data".toByteArray());b.putInt(values.size*2)
        values.forEach(b::putShort)
        val file=File.createTempFile("signed-pcm-", ".wav")
        try {
            file.writeBytes(b.array())
            val actual=AudioFeatureExtractor.readPcm16Wav(file)
            assertEquals(values.size,actual.size)
            values.forEachIndexed { i,v -> assertEquals("PCM sample $i",v/32768.0,actual[i].toDouble(),1e-7) }
            assertTrue(actual.all { it >= -1f && it <= 1f })
        } finally {file.delete()}
    }
}
