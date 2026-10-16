package com.webrtc_phone.android.rtc.channel

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.DataChannel
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class RemoteFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modTime: Long,
)

enum class TransferKind { UPLOAD, DOWNLOAD }
enum class TransferState { QUEUED, PREPARING, ACTIVE, DONE, FAILED, CANCELED;

    val finished get() = this == DONE || this == FAILED || this == CANCELED
}

data class Transfer(
    val id: Long,
    val kind: TransferKind,
    val name: String,
    val remotePath: String,
    val state: TransferState = TransferState.QUEUED,
    val loaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val installAfter: Boolean = false,
    /** 字节/秒，实时速率 */
    val speed: Long = 0,
) {
    val progress: Float get() = if (total > 0) (loaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

data class InstallStatus(val status: String, val message: String)

class FileOpException(message: String) : IOException(message)

/**
 * file-channel 协议客户端（与 Web 端 FileManagerPage.vue / agent webrtc.go 对应）：
 *   list/mkdir/delete → *_reply
 *   upload_start → upload_reply → 16KB 二进制块 → upload_ack(finished)
 *   download_start → download_reply(size) → 二进制块直到收满 size
 *   upload_cancel / download_cancel；install_apk → install_status
 *
 * 上传、下载各自一条串行队列（下载二进制帧不带 request_id，必须串行）。
 * 所有失败路径都会结算任务状态，绝不静默丢弃。
 */
class FileTransferClient(
    context: Context,
    private val channel: SafeChannel,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idSeq = AtomicLong(0)
    private val requestSeq = AtomicLong(0)

    private val _open = MutableStateFlow(channel.isOpen)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _installEvents = MutableSharedFlow<InstallStatus>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val installEvents: SharedFlow<InstallStatus> = _installEvents.asSharedFlow()

    // 简单请求：同类请求串行，一次只有一个等待中的 reply
    private val opMutex = Mutex()
    @Volatile private var pendingReply: Pair<String, CompletableDeferred<JSONObject>>? = null

    // 传输队列
    private val uploadQueue = Channel<Long>(Channel.UNLIMITED)
    private val downloadQueue = Channel<Long>(Channel.UNLIMITED)
    private val jobs = HashMap<Long, Job>()
    private val requests = HashMap<Long, PendingTransfer>()

    private class PendingTransfer(val uri: Uri) {
        @Volatile var reply: CompletableDeferred<JSONObject>? = null
        @Volatile var finished: CompletableDeferred<JSONObject>? = null
        @Volatile var out: OutputStream? = null
        @Volatile var lastProgressAt = System.currentTimeMillis()
    }

    @Volatile private var activeUploadId: Long = -1
    @Volatile private var activeDownloadId: Long = -1

    init {
        channel.observe(object : SafeChannel.Listener {
            override fun onState(state: DataChannel.State) {
                val isOpen = state == DataChannel.State.OPEN
                _open.value = isOpen
                if (state == DataChannel.State.CLOSED) failAll("连接已断开")
            }

            override fun onMessage(data: ByteArray, binary: Boolean) {
                if (binary) onChunk(data) else onText(String(data, Charsets.UTF_8))
            }
        })
        scope.launch { for (id in uploadQueue) runTransfer(id, ::doUpload) }
        scope.launch { for (id in downloadQueue) runTransfer(id, ::doDownload) }
    }

    fun close() {
        failAll("连接已断开")
        scope.cancel()
        requests.values.forEach { r -> runCatching { r.out?.close() } }
    }

    // ---------- 目录操作 ----------

    suspend fun list(path: String): List<RemoteFile> {
        val reply = request("list_reply", JSONObject().put("type", "list").put("path", path))
        if (!reply.optBoolean("success")) throw FileOpException(reply.optString("error", "读取目录失败"))
        val arr = reply.optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                RemoteFile(
                    name = it.optString("name"),
                    path = it.optString("path"),
                    isDir = it.optBoolean("is_dir"),
                    size = it.optLong("size"),
                    modTime = it.optLong("mod_time") * 1000,
                )
            }
        }.sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    suspend fun mkdir(path: String) = simpleOp("mkdir_reply", JSONObject().put("type", "mkdir").put("path", path))

    suspend fun delete(path: String) = simpleOp("delete_reply", JSONObject().put("type", "delete").put("path", path))

    fun installApk(remotePath: String): Boolean = channel.send(
        JSONObject().put("type", "install_apk").put("path", remotePath)
            .put("request_id", requestSeq.incrementAndGet().toString()).toString()
    )

    private suspend fun simpleOp(replyType: String, cmd: JSONObject) {
        val reply = request(replyType, cmd)
        if (!reply.optBoolean("success")) throw FileOpException(reply.optString("error", "操作失败"))
    }

    private suspend fun request(replyType: String, cmd: JSONObject): JSONObject = opMutex.withLock {
        val d = CompletableDeferred<JSONObject>()
        pendingReply = replyType to d
        try {
            if (!channel.send(cmd.toString())) throw FileOpException("文件通道未就绪")
            withTimeout(REPLY_TIMEOUT_MS) { d.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw FileOpException("设备无响应")
        } finally {
            pendingReply = null
        }
    }

    // ---------- 传输 ----------

    fun upload(src: Uri, name: String, size: Long, remotePath: String, installAfter: Boolean = false): Long {
        val id = idSeq.incrementAndGet()
        synchronized(requests) { requests[id] = PendingTransfer(src) }
        _transfers.update {
            it + Transfer(id, TransferKind.UPLOAD, name, remotePath, total = size, installAfter = installAfter)
        }
        uploadQueue.trySend(id)
        return id
    }

    fun download(remotePath: String, dest: Uri, name: String, size: Long): Long {
        val id = idSeq.incrementAndGet()
        synchronized(requests) { requests[id] = PendingTransfer(dest) }
        _transfers.update { it + Transfer(id, TransferKind.DOWNLOAD, name, remotePath, total = size) }
        downloadQueue.trySend(id)
        return id
    }

    fun cancel(id: Long) {
        val t = find(id) ?: return
        if (t.state.finished) return
        val job = synchronized(jobs) { jobs[id] }
        if (job != null) job.cancel(CancellationException("canceled"))
        else settle(id, TransferState.CANCELED, "已取消")
    }

    fun clearFinished() {
        _transfers.update { list -> list.filterNot { it.state.finished } }
    }

    private fun find(id: Long) = _transfers.value.firstOrNull { it.id == id }

    private fun patch(id: Long, block: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    private fun settle(id: Long, state: TransferState, error: String?) {
        patch(id) { if (it.state.finished) it else it.copy(state = state, error = error, speed = 0) }
        synchronized(requests) { requests.remove(id) }?.let { r -> runCatching { r.out?.close() } }
    }

    private suspend fun runTransfer(id: Long, body: suspend (Long, PendingTransfer) -> Unit) {
        val t = find(id) ?: return
        if (t.state.finished) return
        val req = synchronized(requests) { requests[id] } ?: return
        val job = scope.launch {
            try {
                body(id, req)
                settle(id, TransferState.DONE, null)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { onAbort(id, t, canceled = true) }
                settle(id, TransferState.CANCELED, "已取消")
            } catch (e: Exception) {
                Log.w(TAG, "transfer $id failed", e)
                withContext(NonCancellable) { onAbort(id, t, canceled = false) }
                settle(id, TransferState.FAILED, e.message ?: "传输失败")
            }
        }
        synchronized(jobs) { jobs[id] = job }
        job.join()
        synchronized(jobs) { jobs.remove(id) }
    }

    private fun onAbort(id: Long, t: Transfer, canceled: Boolean) {
        if (t.kind == TransferKind.UPLOAD) {
            channel.send(JSONObject().put("type", "upload_cancel").put("path", t.remotePath).toString())
            // 清理远端半截文件
            channel.send(JSONObject().put("type", "delete").put("path", t.remotePath).toString())
        } else {
            channel.send(JSONObject().put("type", "download_cancel").put("path", t.remotePath).toString())
            synchronized(requests) { requests[id] }?.let { r ->
                runCatching { r.out?.close() }
                r.out = null
                // 删除写了一半的本地文件
                runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, r.uri) }
            }
        }
        if (activeUploadId == id) activeUploadId = -1
        if (activeDownloadId == id) activeDownloadId = -1
        Log.d(TAG, "transfer $id aborted canceled=$canceled")
    }

    private suspend fun doUpload(id: Long, req: PendingTransfer) {
        val t = find(id) ?: return
        val resolver = appContext.contentResolver
        patch(id) { it.copy(state = TransferState.PREPARING, loaded = 0) }

        // 1. SHA-256 预计算（agent 端校验完整性）
        val digest = MessageDigest.getInstance("SHA-256")
        var hashed = 0L
        resolver.openInputStream(req.uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var lastNotify = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                hashed += n
                val now = System.currentTimeMillis()
                if (now - lastNotify > PROGRESS_INTERVAL_MS) {
                    lastNotify = now
                    patch(id) { it.copy(loaded = hashed) }
                }
            }
        } ?: throw FileOpException("无法读取本地文件")
        val size = if (t.total > 0) t.total else hashed
        val sha = digest.digest().joinToString("") { "%02x".format(it) }

        // 2. upload_start → upload_reply
        activeUploadId = id
        val reply = CompletableDeferred<JSONObject>().also { req.reply = it }
        req.finished = CompletableDeferred()
        patch(id) { it.copy(state = TransferState.ACTIVE, loaded = 0, total = size) }
        val start = JSONObject()
            .put("type", "upload_start")
            .put("path", t.remotePath)
            .put("size", size)
            .put("sha256", sha)
            .put("install_on_finish", t.installAfter)
        if (!channel.send(start.toString())) throw FileOpException("文件通道未就绪")
        val r = withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() } ?: throw FileOpException("设备无响应")
        if (!r.optBoolean("success")) throw FileOpException(r.optString("error", "设备拒绝上传"))

        // 3. 分块发送 + 流控
        var sent = 0L
        val started = System.currentTimeMillis()
        var lastNotify = 0L
        resolver.openInputStream(req.uri)?.use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            while (sent < size) {
                currentCoroutineContext().ensureActive()
                while (channel.bufferedAmount() > FLOW_CONTROL_BYTES) {
                    if (!channel.isOpen) throw FileOpException("连接已断开")
                    delay(4)
                }
                val n = input.read(buf, 0, minOf(CHUNK_SIZE.toLong(), size - sent).toInt())
                if (n < 0) break
                if (!channel.sendBytes(buf, binary = true, offset = 0, length = n)) throw FileOpException("连接已断开")
                sent += n
                req.lastProgressAt = System.currentTimeMillis()
                val now = req.lastProgressAt
                if (now - lastNotify > PROGRESS_INTERVAL_MS) {
                    lastNotify = now
                    val speed = sent * 1000 / maxOf(1, now - started)
                    patch(id) { it.copy(loaded = sent, speed = speed) }
                }
            }
        } ?: throw FileOpException("无法读取本地文件")
        patch(id) { it.copy(loaded = sent) }

        // 4. 等待 upload_ack(finished)；期间 ack 进度会刷新 lastProgressAt
        val ack = awaitWithStall(req, req.finished!!)
        activeUploadId = -1
        if (!ack.optBoolean("success", true)) throw FileOpException(ack.optString("error", "文件校验失败"))
    }

    private suspend fun doDownload(id: Long, req: PendingTransfer) {
        val t = find(id) ?: return
        val out = withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(req.uri, "wt")
        } ?: throw FileOpException("无法写入本地文件")
        req.out = BufferedOutputStream(out, 256 * 1024)
        val reply = CompletableDeferred<JSONObject>().also { req.reply = it }
        req.finished = CompletableDeferred()
        activeDownloadId = id
        patch(id) { it.copy(state = TransferState.ACTIVE, loaded = 0) }
        val cmd = JSONObject().put("type", "download_start").put("path", t.remotePath)
            .put("request_id", requestSeq.incrementAndGet().toString())
        if (!channel.send(cmd.toString())) throw FileOpException("文件通道未就绪")
        val r = withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() } ?: throw FileOpException("设备无响应")
        if (!r.optBoolean("success")) throw FileOpException(r.optString("error", "下载失败"))
        val total = r.optLong("size")
        patch(id) { it.copy(total = total) }
        downloadStarted = System.currentTimeMillis()
        if (total > 0) awaitWithStall(req, req.finished!!)
        withContext(Dispatchers.IO) {
            req.out?.flush()
            req.out?.close()
        }
        req.out = null
        activeDownloadId = -1
    }

    @Volatile private var downloadStarted = 0L

    /** 等待完成信号；连续 [STALL_TIMEOUT_MS] 无字节进展判失败 */
    private suspend fun awaitWithStall(req: PendingTransfer, d: CompletableDeferred<JSONObject>): JSONObject {
        req.lastProgressAt = System.currentTimeMillis()
        while (true) {
            val r = withTimeoutOrNull(1000) { d.await() }
            if (r != null) return r
            if (!channel.isOpen) throw FileOpException("连接已断开")
            if (System.currentTimeMillis() - req.lastProgressAt > STALL_TIMEOUT_MS) throw FileOpException("传输超时")
        }
    }

    // ---------- 入站消息（单线程 dispatcher 上执行，保证顺序） ----------

    private var lastDownloadNotify = 0L

    private fun onChunk(bytes: ByteArray) {
        val id = activeDownloadId
        if (id < 0) return
        val req = synchronized(requests) { requests[id] } ?: return
        val out = req.out ?: return
        try {
            out.write(bytes)
        } catch (e: IOException) {
            req.finished?.completeExceptionally(FileOpException("写入失败：${e.message}"))
            return
        }
        req.lastProgressAt = System.currentTimeMillis()
        var done = false
        patch(id) {
            val loaded = it.loaded + bytes.size
            done = it.total in 1..loaded
            val now = req.lastProgressAt
            if (done || now - lastDownloadNotify > PROGRESS_INTERVAL_MS) {
                lastDownloadNotify = now
                it.copy(loaded = loaded, speed = loaded * 1000 / maxOf(1, now - downloadStarted))
            } else it.copy(loaded = loaded)
        }
        if (done) req.finished?.complete(JSONObject().put("success", true))
    }

    private fun onText(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (val type = msg.optString("type")) {
            "list_reply", "mkdir_reply", "delete_reply" -> {
                val p = pendingReply
                if (p != null && p.first == type) p.second.complete(msg)
            }
            "upload_reply" -> activeReq(activeUploadId)?.reply?.complete(msg)
            "upload_ack" -> {
                val req = activeReq(activeUploadId) ?: return
                req.lastProgressAt = System.currentTimeMillis()
                if (msg.optBoolean("finished")) req.finished?.complete(msg)
            }
            "download_reply" -> activeReq(activeDownloadId)?.reply?.complete(msg)
            "install_status" -> _installEvents.tryEmit(
                InstallStatus(msg.optString("status"), msg.optString("message"))
            )
            else -> Log.d(TAG, "unhandled file-channel message: $type")
        }
    }

    private fun activeReq(id: Long) = if (id < 0) null else synchronized(requests) { requests[id] }

    private fun failAll(reason: String) {
        _transfers.value.filterNot { it.state.finished }.forEach { t ->
            val job = synchronized(jobs) { jobs[t.id] }
            if (job != null) {
                activeReq(t.id)?.let { r ->
                    r.reply?.completeExceptionally(FileOpException(reason))
                    r.finished?.completeExceptionally(FileOpException(reason))
                }
            } else {
                settle(t.id, TransferState.FAILED, reason)
            }
        }
        pendingReply?.second?.completeExceptionally(FileOpException(reason))
    }

    companion object {
        private const val TAG = "FileTransfer"
        private const val CHUNK_SIZE = 16 * 1024
        private const val FLOW_CONTROL_BYTES = 1024L * 1024
        private const val REPLY_TIMEOUT_MS = 15_000L
        private const val STALL_TIMEOUT_MS = 30_000L
        private const val PROGRESS_INTERVAL_MS = 150L
    }
}
