package com.webrtc_phone.android.rtc.channel

import org.json.JSONObject

/**
 * 双向剪贴板（clipboard-channel）。纯逻辑：本机剪贴板读写由调用方负责。
 * 防回环：记住最近一次同步过的文本，云端回声 / 重复广播一律忽略。
 */
class ClipboardProtocol(
    private val sender: TextSender,
    private val enabled: () -> Boolean,
) {
    @Volatile
    var lastSynced: String? = null
        private set

    /** 本机 → 云手机。paste=true 时云端写入后自动粘贴（文本下发场景） */
    fun push(text: String, paste: Boolean = false, force: Boolean = false): Boolean {
        if (!enabled() || text.isEmpty()) return false
        if (!force && !paste && text == lastSynced) return false
        val ok = sender.send(
            JSONObject()
                .put("type", "set_clipboard")
                .put("text", text)
                .put("paste", paste)
                .put("source", "local")
                .toString()
        )
        if (ok) lastSynced = text
        return ok
    }

    /** 请求云端当前剪贴板（结果通过 [parseIncoming] 回来） */
    fun requestRemote(): Boolean {
        if (!enabled()) return false
        return sender.send(JSONObject().put("type", "get_clipboard").toString())
    }

    /**
     * 解析云端消息；返回应写入本机剪贴板的文本，或 null（无需处理）。
     * source=keyboard 是 IME 落屏产生的临时剪贴板，不同步到本机。
     */
    fun parseIncoming(raw: String, forceAccept: Boolean = false): String? {
        if (!enabled()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optString("type") != "clipboard") return null
        if (!o.has("text") || o.isNull("text")) return null
        val text = o.optString("text")
        if (text.isEmpty()) return null
        if (o.optString("source") == "keyboard") return null
        if (!forceAccept && text == lastSynced) return null
        lastSynced = text
        return text
    }

    fun reset() {
        lastSynced = null
    }
}
