package com.webrtc_phone.android.data.repo

import android.content.Context
import android.net.Uri
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.data.api.CloudPhoneApi
import com.webrtc_phone.android.data.model.ServerFile
import com.webrtc_phone.android.data.model.ServerFileOps
import com.webrtc_phone.android.data.model.ServerTaskType
import com.webrtc_phone.android.data.model.SubTaskStatus
import com.webrtc_phone.android.data.model.TaskDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okio.BufferedSink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** 一次服务器中转操作（上传 / 下发）的 UI 状态 */
data class ServerOp(
    val id: Long,
    val name: String,
    val kind: Kind,
    val state: State = State.ACTIVE,
    val loaded: Long = 0,
    val total: Long = 0,
    val message: String = "",
) {
    enum class Kind { UPLOAD, DISPATCH }
    enum class State { ACTIVE, DONE, FAILED }
    val progress: Float get() = if (total > 0) (loaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * 服务器中转文件模式（大多数云手机厂商的做法）：
 *   本地文件 →(POST /upload)→ 服务器盘 →(POST /api/tasks: install/push_file)→ 设备拉取/安装。
 * 与 [FileTransferClient] 的 WebRTC 直连互补。
 *
 * 会话级（每个登录用户一份），由 AppContainer.userScope 持有。所有网络错误抛 [ApiException]，UI 只看 message。
 */
class ServerFileRepository(
    context: Context,
    private val api: CloudPhoneApi,
    private val endpoint: ServerEndpoint,
) {
    private val appContext = context.applicationContext
    private val idSeq = AtomicLong(0)

    private val _files = MutableStateFlow<List<ServerFile>>(emptyList())
    val files: StateFlow<List<ServerFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _ops = MutableStateFlow<List<ServerOp>>(emptyList())
    val ops: StateFlow<List<ServerOp>> = _ops.asStateFlow()

    // ---------- 列表 / 删除 ----------

    suspend fun refresh() {
        _loading.value = true
        try {
            _files.value = api.serverFiles()
        } finally {
            _loading.value = false
        }
    }

    suspend fun delete(name: String) {
        api.deleteServerFile(name)
        _files.update { it.filterNot { f -> f.name == name } }
    }

    // ---------- 上传 ----------

    /**
     * 上传本地文件到服务器盘。带进度；完成后刷新列表。返回操作 id。
     * 失败会把 op 结算为 FAILED 并抛出，调用方可据此提示。
     */
    suspend fun upload(uri: Uri, name: String, size: Long) {
        val id = idSeq.incrementAndGet()
        _ops.update { it + ServerOp(id, name, ServerOp.Kind.UPLOAD, total = size) }
        try {
            val body = streamingBody(uri, size) { sent ->
                patch(id) { it.copy(loaded = sent) }
            }
            api.uploadServerFile(name, body)
            patch(id) { it.copy(state = ServerOp.State.DONE, loaded = if (size > 0) size else it.loaded) }
            refresh()
        } catch (e: CancellationException) {
            patch(id) { it.copy(state = ServerOp.State.FAILED, message = "已取消") }
            throw e
        } catch (e: Exception) {
            val msg = (e as? ApiException)?.message ?: e.message ?: "上传失败"
            patch(id) { it.copy(state = ServerOp.State.FAILED, message = msg) }
            throw if (e is ApiException) e else ApiException.from(e)
        }
    }

    private fun streamingBody(uri: Uri, size: Long, onProgress: (Long) -> Unit): RequestBody {
        val resolver = appContext.contentResolver
        val media = "application/octet-stream".toMediaType()
        return object : RequestBody() {
            override fun contentType() = media
            override fun contentLength(): Long = if (size > 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("无法读取本地文件")
                input.use {
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastNotify = 0L
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 150) {
                            lastNotify = now
                            onProgress(sent)
                        }
                    }
                    onProgress(sent)
                }
            }
        }
    }

    // ---------- 下发到设备 ----------

    /**
     * 把服务器盘里的某个文件下发到指定设备：
     *   apk → install；其它 → push_file 到 destPath。
     * 建任务后轮询 /api/tasks/details 直到该设备子任务 finished；返回最终子任务状态。
     * destPath 仅 push_file 需要；传空时用默认 /sdcard/Download/<name>。
     */
    suspend fun dispatch(file: ServerFile, deviceId: String, destPath: String? = null): SubTaskStatus {
        val id = idSeq.incrementAndGet()
        _ops.update { it + ServerOp(id, file.name, ServerOp.Kind.DISPATCH, message = "准备下发…") }
        try {
            val type = ServerFileOps.taskTypeFor(file.name)
            val signed = api.serverFileSignedUrl(file.name)
            val payload = ServerFileOps.absoluteUrl(endpoint.httpBase, signed)
            val dest = if (type == ServerTaskType.PUSH_FILE)
                (destPath?.trim()?.ifBlank { null } ?: ServerFileOps.defaultDestPath(file.name)) else ""
            patch(id) { it.copy(message = if (type == ServerTaskType.INSTALL) "正在安装…" else "正在下发…") }
            val taskId = api.createFileTask(type.wire, listOf(deviceId), payload, dest)
            // 轮询直到设备子任务结束或超时
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            var last: SubTaskStatus? = null
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
                val details = runCatching { api.taskDetails(taskId) }.getOrNull() ?: continue
                val sub = details.devices[deviceId] ?: continue
                last = sub
                patch(id) { it.copy(message = statusLabel(sub)) }
                if (sub.finished) {
                    patch(id) {
                        it.copy(
                            state = if (sub.ok) ServerOp.State.DONE else ServerOp.State.FAILED,
                            message = if (sub.ok) "完成" else (sub.result.ifBlank { "失败" }),
                        )
                    }
                    return sub
                }
            }
            patch(id) { it.copy(state = ServerOp.State.FAILED, message = "任务超时") }
            return last ?: SubTaskStatus(deviceId, "failed", 0, "任务超时")
        } catch (e: CancellationException) {
            patch(id) { it.copy(state = ServerOp.State.FAILED, message = "已取消") }
            throw e
        } catch (e: Exception) {
            val msg = (e as? ApiException)?.message ?: e.message ?: "下发失败"
            patch(id) { it.copy(state = ServerOp.State.FAILED, message = msg) }
            throw if (e is ApiException) e else ApiException.from(e)
        }
    }

    fun clearFinished() {
        _ops.update { list -> list.filterNot { it.state != ServerOp.State.ACTIVE } }
    }

    // ---------- 批量下发（管理页：一次任务下发到多台设备） ----------

    private val _task = MutableStateFlow<TaskDetails?>(null)
    /** 当前批量任务的实时状态（每台设备子任务），null = 无进行中任务 */
    val task: StateFlow<TaskDetails?> = _task.asStateFlow()

    private val _dispatching = MutableStateFlow(false)
    val dispatching: StateFlow<Boolean> = _dispatching.asStateFlow()

    /**
     * 把服务器文件一次性下发到多台设备（对齐 Web 端批量安装/传输）。
     * 建一个带 targets 数组的任务，轮询 /api/tasks/details 刷新每台设备状态，
     * 直到全部设备子任务结束或超时。期间通过 [task] 暴露进度。
     */
    suspend fun dispatchBatch(file: ServerFile, deviceIds: List<String>, destPath: String?) {
        if (deviceIds.isEmpty()) return
        _dispatching.value = true
        _task.value = null
        try {
            val type = ServerFileOps.taskTypeFor(file.name)
            val signed = api.serverFileSignedUrl(file.name)
            val payload = ServerFileOps.absoluteUrl(endpoint.httpBase, signed)
            val dest = if (type == ServerTaskType.PUSH_FILE)
                (destPath?.trim()?.ifBlank { null } ?: ServerFileOps.defaultDestPath(file.name)) else ""
            val taskId = api.createFileTask(type.wire, deviceIds, payload, dest)
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val details = runCatching { api.taskDetails(taskId) }.getOrNull()
                if (details != null) {
                    _task.value = details
                    // 所有涉及设备都结束则停止轮询
                    val subs = deviceIds.map { details.devices[it] }
                    if (subs.all { it != null && it.finished }) return
                }
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: Exception) {
            throw if (e is ApiException) e else ApiException.from(e)
        } finally {
            _dispatching.value = false
        }
    }

    fun clearTask() {
        _task.value = null
    }

    private fun statusLabel(s: SubTaskStatus): String = when (s.status.lowercase()) {
        "queued" -> "排队中"
        "running" -> if (s.progress > 0) "处理中 ${s.progress}%" else "处理中"
        "success", "done", "completed" -> "完成"
        "failed", "error" -> s.result.ifBlank { "失败" }
        else -> s.status
    }

    private fun patch(id: Long, block: (ServerOp) -> ServerOp) {
        _ops.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_200L
        private const val POLL_TIMEOUT_MS = 180_000L
    }
}
