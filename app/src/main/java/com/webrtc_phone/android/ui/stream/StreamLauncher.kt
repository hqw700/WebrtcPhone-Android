package com.webrtc_phone.android.ui.stream

import android.content.Context
import android.content.Intent
import com.webrtc_phone.android.rtc.StreamTarget

/**
 * 投屏页启动器。StreamTarget 含访问凭据，只放在进程内存中传递，
 * 不进 Intent extras（避免进入最近任务快照 / 被其他组件读取）。
 * 进程被杀后投屏页拿不到目标会直接关闭，回到设备列表重新进入即可。
 */
object StreamLauncher {
    @Volatile
    var pending: StreamTarget? = null
        private set

    fun launch(context: Context, target: StreamTarget) {
        pending = target
        context.startActivity(
            Intent(context, StreamActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
        )
    }

    fun update(target: StreamTarget) {
        pending = target
    }

    fun clear() {
        pending = null
    }
}
