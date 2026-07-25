package com.plaincast.app.signaling

import com.plaincast.app.model.ClientType
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCapabilitiesTest {
    @Test fun browserPublishesVoiceAndVideoButReceivesSharedAudio() {
        val join = JoinPayload(
            token = "0123456789abcdef0123456789abcdef",
            displayName = "Desk browser",
            deviceName = "Chrome browser",
            clientType = ClientType.Browser,
            capabilities = Capabilities.browser(),
        )
        val decoded = SignalJson.json.decodeFromJsonElement<JoinPayload>(SignalJson.payload(join))
        assertEquals(ClientType.Browser, decoded.clientType)
        assertTrue(decoded.capabilities.receiveVoice)
        assertTrue(decoded.capabilities.sendVoice)
        assertTrue(decoded.capabilities.receiveScreen)
        assertTrue(decoded.capabilities.publishScreen)
        assertTrue(decoded.capabilities.receiveAudio)
        assertFalse(decoded.capabilities.publishAudio)
        assertTrue(decoded.capabilities.isValidFor(ClientType.Browser))
    }

    @Test fun browserMayJoinReceiveOnlyWhenCaptureIsUnavailable() {
        val receiveOnly = Capabilities(true, false, true, false, true, false)
        assertTrue(receiveOnly.isValidFor(ClientType.Browser))
    }
    @Test fun browserCannotClaimAndroidOnlyDeviceAudioPublishing() {
        val invalid = Capabilities(true, true, true, true, true, true)
        assertFalse(invalid.isValidFor(ClientType.Browser))
    }

}
