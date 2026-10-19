package com.webrtc_phone.android.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 反馈分类（对齐 web-app FeedbackModal） */
private val FEEDBACK_TYPES = listOf(
    "bug" to "🐞 Bug",
    "feature" to "💡 建议",
    "consult" to "💬 咨询",
    "other" to "📌 其它",
)

private const val FEEDBACK_URL = "https://license.webrtc-phone.com/api/feedback"

/** 本地提交冷却，减少误触重复提交（服务端另有 IP 60s/24h 频控与蜜罐反爬） */
private var lastFeedbackSubmitAt = 0L

/**
 * 意见反馈弹窗：复用 web-app 的 webhook 通道（license.webrtc-phone.com/api/feedback → 企业微信机器人）。
 * 防滥用：
 *  - 客户端：honeypot 恒空、内容 5–1000 字、本地 30s 提交冷却；
 *  - 服务端：honeypot 命中静默伪装成功、IP 60s/24h 频控、长度校验（真正的防线）。
 */
@Composable
fun FeedbackDialog(versionName: String, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf("bug") }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trimmed = content.trim()

    fun submit() {
        val now = System.currentTimeMillis()
        if (now - lastFeedbackSubmitAt < 30_000) {
            error = "提交过于频繁，请稍后再试"
            return
        }
        submitting = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { postFeedback(type, trimmed, contact.trim(), versionName) }
            submitting = false
            if (result.success) {
                lastFeedbackSubmitAt = System.currentTimeMillis()
                done = true
            } else {
                error = result.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (done) "已收到，谢谢反馈" else "意见反馈") },
        text = {
            if (done) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("你的反馈已发送给作者，我们会认真查看。", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FEEDBACK_TYPES.forEach { (v, label) ->
                            FilterChip(selected = type == v, onClick = { type = v }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        content, { if (it.length <= 1000) content = it },
                        label = { Text("问题描述（至少 5 字）") },
                        supportingText = { Text("${content.length} / 1000") },
                        minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        contact, { if (it.length <= 100) contact = it },
                        label = { Text("联系方式（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("完成") }
            } else {
                Button(enabled = !submitting && trimmed.length >= 5, onClick = { submit() }) {
                    if (submitting) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(0.dp))
                    }
                    Text(if (submitting) "提交中…" else "提交")
                }
            }
        },
        dismissButton = { if (!done) TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") } },
    )
}

private data class FeedbackResult(val success: Boolean, val message: String)

/** 诊断信息随反馈上报（不含任何凭据）；honeypot 恒为空字符串 */
private fun postFeedback(type: String, content: String, contact: String, versionName: String): FeedbackResult {
    val meta = JSONObject()
        .put("version", versionName)
        .put("platform", "android")
        .put("userAgent", "WebrtcPhone-Android/$versionName (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})")
        .put("screen", "")
        .put("language", java.util.Locale.getDefault().toLanguageTag())
    val payload = JSONObject()
        .put("type", type)
        .put("content", content)
        .put("contact", contact)
        .put("meta", meta)
        .put("honeypot", "") // 反爬蜜罐：客户端恒空
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    val req = Request.Builder()
        .url(FEEDBACK_URL)
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    return try {
        client.newCall(req).execute().use { r ->
            val bodyStr = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(bodyStr) }.getOrNull()
            val success = r.isSuccessful && o?.optBoolean("success") == true
            FeedbackResult(success, o?.optString("message").orEmpty().ifBlank {
                if (success) "提交成功" else "提交失败（${r.code}）"
            })
        }
    } catch (e: Exception) {
        FeedbackResult(false, "网络错误，请稍后再试")
    }
}
