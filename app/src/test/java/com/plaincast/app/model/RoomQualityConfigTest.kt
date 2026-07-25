package com.plaincast.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoomQualityConfigTest {
    @Test fun defaultConfigurationIsAudioFirstAndLowLatency() {
        val config = RoomQualityConfig()
        assertEquals(2, config.audioChannelCount)
        assertEquals(20, config.audioFrameMs)
        assertEquals(60, config.audioTargetDelayMs)
        assertEquals(100, config.audioMaxBufferedMs)
        assertEquals(12, config.screenFps)
        assertEquals(700, config.screenMaxBitrateKbps)
    }

    @Test fun invalidBufferConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RoomQualityConfig(audioTargetDelayMs = 100, audioMaxBufferedMs = 80)
        }
    }
}
