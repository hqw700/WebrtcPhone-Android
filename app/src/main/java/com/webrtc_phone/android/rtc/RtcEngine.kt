package com.webrtc_phone.android.rtc

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * 进程级 WebRTC 引擎：EglBase 与 PeerConnectionFactory 只创建一次、跨会话复用。
 * - 重连/切换画质时无需重建工厂（省去数百毫秒初始化与硬解码器探测）；
 * - EglBase 被渲染器持有，若随会话释放会导致重连后黑屏。
 * 本端只接收不发送视频，编码器用软件工厂即可（不会实际使用）。
 */
class RtcEngine(private val context: Context) {

    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        // INFO 级日志经 JNI 逐行回调，视频路径上会明显拖慢解码；常态保持 WARNING
        Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)

        // 云手机的声音是媒体声，必须走 USAGE_MEDIA；默认通话流会被路由到听筒
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()

        val f = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release() // 工厂已持有引用
        factory = f
        return f
    }
}
