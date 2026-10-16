package com.webrtc_phone.android.rtc

import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.data.model.AccessMode
import com.webrtc_phone.android.data.settings.StreamSettings

/** 连接鉴权方式 */
sealed interface StreamAuth {
    /** 登录用户（Authorization 头） */
    data class User(val token: String) : StreamAuth

    /** 卡密/分享访客（服务端仅支持 query 参数鉴权） */
    data class Share(val shareToken: String, val password: String) : StreamAuth
}

/** 云手机摄像头作为视频源（监控模式）的参数 */
data class CameraParams(
    val facing: String = "back",
    val cameraId: String = "0",
    val size: String = "1920x1080",
    val fps: Int = 30,
    val zoom: Double = 1.0,
    /** 监控模式带环境音（设备麦克风） */
    val micAudio: Boolean = true,
)

sealed interface StreamMode {
    data object Screen : StreamMode
    data class Camera(val params: CameraParams) : StreamMode
}

/** 一次投屏会话的全部输入参数（不可变；修改画质/镜头 = 以新 target 重连） */
data class StreamTarget(
    val endpoint: ServerEndpoint,
    val deviceId: String,
    val deviceName: String,
    val auth: StreamAuth,
    val accessMode: AccessMode = AccessMode.FULL,
    val allowClipboard: Boolean = true,
    val allowFileTx: Boolean = true,
    val allowTerminal: Boolean = true,
    val mode: StreamMode = StreamMode.Screen,
    val settings: StreamSettings = StreamSettings(),
    /** 设备列表里已知的分辨率，首帧到达前用于占位比例 */
    val displayHint: Pair<Int, Int>? = null,
) {
    val viewOnly: Boolean get() = accessMode == AccessMode.VIEW_ONLY
    val isGuest: Boolean get() = auth is StreamAuth.Share
    val isCamera: Boolean get() = mode is StreamMode.Camera
}
