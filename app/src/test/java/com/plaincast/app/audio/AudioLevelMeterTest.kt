package com.plaincast.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelMeterTest {
    @Test
    fun silenceReportsFloor() {
        val result = AudioLevelMeter.measurePcm16(ByteArray(480))

        assertEquals(0f, result.normalized, 0.0001f)
        assertEquals(-120f, result.rmsDbfs, 0.0001f)
        assertEquals(240, result.sampleCount)
    }

    @Test
    fun fullScaleSignalReportsHighLevel() {
        val data = ByteArray(480)
        var index = 0
        while (index < data.size) {
            data[index] = 0xff.toByte()
            data[index + 1] = 0x7f
            index += 2
        }

        val result = AudioLevelMeter.measurePcm16(data)

        assertTrue(result.normalized > 0.95f)
        assertTrue(result.rmsDbfs > -0.1f)
        assertTrue(result.peakDbfs > -0.1f)
    }
}
