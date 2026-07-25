package com.plaincast.app.rtc

import android.content.Context
import android.media.MediaRecorder
import com.plaincast.app.diagnostics.DiagnosticsRepository
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

object RtcEngine {
    lateinit var eglBase: EglBase
        private set
    lateinit var factory: PeerConnectionFactory
        private set

    private lateinit var audioDeviceModule: JavaAudioDeviceModule

    fun initialize(context: Context, diagnostics: DiagnosticsRepository) {
        if (::factory.isInitialized) return
        val appContext = context.applicationContext
        eglBase = EglBase.create()
        val options = PeerConnectionFactory.InitializationOptions.builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseLowLatency(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setSamplesReadyCallback { samples ->
                diagnostics.onMicrophoneSamples(
                    data = samples.data,
                    audioFormat = samples.audioFormat,
                    channelCount = samples.channelCount,
                    sampleRate = samples.sampleRate,
                )
            }
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() = diagnostics.onMicrophoneRecordingState(true)
                override fun onWebRtcAudioRecordStop() = diagnostics.onMicrophoneRecordingState(false)
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() = diagnostics.onAudioPlayoutState(true)
                override fun onWebRtcAudioTrackStop() = diagnostics.onAudioPlayoutState(false)
            })
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    diagnostics.onMicrophoneCaptureError("Microphone initialization failed: $errorMessage")
                }

                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String,
                ) {
                    diagnostics.onMicrophoneCaptureError("Microphone start failed ($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    diagnostics.onMicrophoneCaptureError("Microphone capture failed: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                    diagnostics.onVoicePlayoutError("Voice playback initialization failed: $errorMessage")
                }

                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    errorMessage: String,
                ) {
                    diagnostics.onVoicePlayoutError("Voice playback start failed ($errorCode): $errorMessage")
                }

                override fun onWebRtcAudioTrackError(errorMessage: String) {
                    diagnostics.onVoicePlayoutError("Voice playback failed: $errorMessage")
                }
            })
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }
}
