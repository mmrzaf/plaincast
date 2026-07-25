package com.plaincast.app.rtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SimpleSdpObserver(
    private val onCreateSuccessBlock: (SessionDescription) -> Unit = {},
    private val onSetSuccessBlock: () -> Unit = {},
    private val onFailureBlock: (String) -> Unit = {},
) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = onCreateSuccessBlock(desc)
    override fun onSetSuccess() = onSetSuccessBlock()
    override fun onCreateFailure(error: String) = onFailureBlock(error)
    override fun onSetFailure(error: String) = onFailureBlock(error)
}
