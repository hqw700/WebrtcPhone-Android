package com.webrtc_phone.android.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.core.net.await
import com.webrtc_phone.android.data.api.CloudPhoneApi
import com.webrtc_phone.android.data.model.AuthSession
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.Me
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.model.TagOps
import com.webrtc_phone.android.data.model.TagsPayload
import com.webrtc_phone.android.data.realtime.DashboardEvent
import com.webrtc_phone.android.data.realtime.DashboardSocket
import com.webrtc_phone.android.data.realtime.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 快照位图 + 版本号（版本变化才触发 UI 重组，避免闪烁） */
data class Snapshot(val bitmap: Bitmap, val version: Long)

/**
 * 设备大盘数据源（单个登录会话一份）。
 * 实时推送为主（DashboardSocket），HTTP 轮询仅作兜底：
 * - 设备列表：WS 推送；WS 断开时每 15s 拉取；
 * - 快照：WS 推送 / 通知后按 ETag 拉取；兜底 20s 轮询（304 不下载）。
 * 页面不可见时调用 [setActive](false) 关闭长连接与轮询，投屏期间不与视频流抢带宽。
 */
class DeviceRepository(
    private val session: AuthSession,
    private val api: CloudPhoneApi,
    private val socket: DashboardSocket,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, Snapshot>> = _snapshots.asStateFlow()

    private val _tags = MutableStateFlow(TagsPayload.EMPTY)
    val tags: StateFlow<TagsPayload> = _tags.asStateFlow()

    // 标签写操作串行化：所有改标签的方法都在锁内「读最新 _tags → 改 → POST」，
    // 否则两个并发写（如重命名标签与给设备分配标签）会各自基于旧快照构建整份 payload，
    // 服务端 admin 路径是整体覆盖，后写的会把先写的改动抹掉（丢更新）。
    private val tagWriteMutex = Mutex()

    // 保存在途计数：>0 时忽略服务端 tags_update 广播与 loadMeta 拉取，
    // 避免「本地刚保存的最新值」被一条稍慢到达的旧广播/旧 GET 回退覆盖。
    @Volatile
    private var tagSaveInFlight = 0

    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<ApiException?>(null)
    val error: StateFlow<ApiException?> = _error.asStateFlow()

    val socketState: StateFlow<SocketState> get() = socket.state

    private val etags = ConcurrentHashMap<String, String>()
    private val snapshotLimiter = Semaphore(3)

    @Volatile
    private var loadedOnce = false

    fun setActive(active: Boolean) {
        if (active) {
            if (activeJob?.isActive == true) return
            activeJob = scope.launch {
                launch { refresh(showLoading = !loadedOnce) }
                launch { loadMeta() }
                socket.start(this)
                launch { socket.events.collect(::onEvent) }
                launch { fallbackPoll() }
            }
        } else {
            socket.stop()
            activeJob?.cancel()
            activeJob = null
        }
    }

    fun close() {
        setActive(false)
        scope.coroutineContext.cancelChildren()
    }

    suspend fun refresh(showLoading: Boolean = true) {
        if (showLoading) _loading.value = true
        try {
            val list = api.devices()
            _devices.value = list
            _error.value = null
            loadedOnce = true
            refreshSnapshots(list)
        } catch (e: ApiException) {
            _error.value = e
        } finally {
            _loading.value = false
        }
    }

    suspend fun loadMeta() {
        runCatching { api.me() }.onSuccess { _me.value = it }
        if (session.isAdmin) {
            // 保存在途时不要用 GET 结果覆盖：此刻服务端可能还没落盘/广播完本地最新改动
            runCatching { api.tags() }.onSuccess { if (tagSaveInFlight == 0) _tags.value = it }
        }
    }

    private suspend fun fallbackPoll() {
        var tick = 0
        while (true) {
            delay(5_000)
            tick++
            if (socket.state.value != SocketState.CONNECTED && tick % 3 == 0) {
                refresh(showLoading = false)
            } else if (tick % 4 == 0) {
                refreshSnapshots(_devices.value)
            }
        }
    }

    private fun onEvent(e: DashboardEvent) {
        when (e) {
            is DashboardEvent.Devices -> {
                _devices.value = e.devices
                loadedOnce = true
                val ids = e.devices.mapTo(HashSet()) { it.id }
                _snapshots.update { m -> m.filterKeys { it in ids } }
            }
            is DashboardEvent.SnapshotData -> scope.launch { applySnapshot(e.deviceId, e.jpeg, null) }
            is DashboardEvent.SnapshotUpdated -> {
                if (e.hash.isEmpty() || etags[e.deviceId]?.trim('"') != e.hash) {
                    scope.launch { fetchSnapshot(e.deviceId) }
                }
            }
            is DashboardEvent.Tags -> if (tagSaveInFlight == 0) _tags.value = e.payload
            is DashboardEvent.ScreenState -> _devices.update { list ->
                list.map { if (it.id == e.deviceId) it.copy(screenState = e.state) else it }
            }
        }
    }

    private fun refreshSnapshots(list: List<Device>) {
        list.filter { it.online }.forEach { d -> scope.launch { fetchSnapshot(d.id) } }
    }

    private suspend fun fetchSnapshot(deviceId: String) = snapshotLimiter.withPermit {
        val client = api.client
        val req = Request.Builder()
            .url(client.endpoint.url("/snapshots/" + Uri.encode(deviceId) + ".jpg"))
            .header("Authorization", "Bearer ${session.token}")
            .apply { etags[deviceId]?.let { header("If-None-Match", it) } }
            .build()
        try {
            client.http.newCall(req).await().use { r ->
                if (r.code == 304 || !r.isSuccessful) return@use
                val bytes = withContext(Dispatchers.IO) { r.body?.bytes() } ?: return@use
                applySnapshot(deviceId, bytes, r.header("ETag"))
            }
        } catch (e: Exception) {
            Log.d(TAG, "snapshot $deviceId failed: ${e.message}")
        }
    }

    private suspend fun applySnapshot(deviceId: String, jpeg: ByteArray, etag: String?) {
        val bmp = withContext(Dispatchers.Default) { decodeThumb(jpeg) } ?: return
        if (etag != null) etags[deviceId] = etag
        _snapshots.update { it + (deviceId to Snapshot(bmp, System.nanoTime())) }
    }

    /** 缩略图按目标宽度降采样，宫格展示无需原尺寸，显著降低内存 */
    private fun decodeThumb(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
        return BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    // ---------- 设备 / 标签管理 ----------

    suspend fun deleteDevice(deviceId: String) {
        api.deleteDevice(deviceId)
        _devices.update { l -> l.filterNot { it.id == deviceId } }
    }

    suspend fun saveTags(payload: TagsPayload) {
        mutateTags { payload }
    }

    suspend fun upsertTag(tag: Tag) = mutateTags { TagOps.upsert(it, tag) }

    suspend fun deleteTag(tagId: String) = mutateTags { TagOps.delete(it, tagId) }

    suspend fun setDeviceTags(deviceId: String, tagIds: List<String>) =
        mutateTags { TagOps.setDeviceTags(it, deviceId, tagIds) }

    /**
     * 标签写操作的统一入口：串行执行，锁内基于「最新」_tags 计算新值，
     * 乐观更新后 POST，失败回滚。保存在途期间屏蔽服务端广播/GET 回退（见 tagSaveInFlight）。
     *
     * 为什么要锁内取 _tags：两个并发写若各自在锁外拿旧快照，服务端整体覆盖会丢其中一个改动。
     */
    private suspend fun mutateTags(transform: (TagsPayload) -> TagsPayload) {
        tagWriteMutex.withLock {
            val old = _tags.value
            val next = transform(old)
            if (next == old) return
            tagSaveInFlight++
            _tags.value = next
            try {
                api.saveTags(next)
            } catch (e: ApiException) {
                _tags.value = old
                throw e
            } finally {
                tagSaveInFlight--
            }
        }
    }

    companion object {
        private const val TAG = "DeviceRepository"
        private const val THUMB_WIDTH = 360
        fun newTagId(): String = "tag_" + UUID.randomUUID().toString().take(8)
    }
}
