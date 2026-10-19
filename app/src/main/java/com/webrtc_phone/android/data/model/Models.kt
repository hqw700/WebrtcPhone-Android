package com.webrtc_phone.android.data.model

/** 登录态。token 仅保存在 SecureStore（Keystore 加密），内存中由 SessionRepository 持有 */
data class AuthSession(
    val serverUrl: String,
    val token: String,
    val username: String,
    val role: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

/** /api/me */
data class Me(
    val username: String,
    val role: String,
    val expiresAt: Long?,
    val policy: UserPolicy,
    val leases: List<Lease>,
)

data class Lease(val deviceId: String, val expiresAt: Long?, val remainingSeconds: Long)

/** 用户能力管控（服务端在信令层强制，客户端据此隐藏/禁用入口以免误导） */
data class UserPolicy(
    val forbidBitrate: Boolean = false,
    val forbidFps: Boolean = false,
    val forbidResolution: Boolean = false,
    val forbidAudio: Boolean = false,
    val forbidFilePush: Boolean = false,
    val forbidTerminal: Boolean = false,
    val forbidShare: Boolean = false,
)

data class DisplayInfo(val width: Int, val height: Int)

data class CameraLens(
    val id: String,
    val facing: String,
    val type: String,
    val name: String,
    val zoom: Double,
)

data class DeviceInfo(
    val model: String = "",
    val brand: String = "",
    val androidVersion: String = "",
    val sdkInt: String = "",
    val cpuAbi: String = "",
    val memTotalMb: Long = 0,
    val isEmulator: Boolean = false,
    val agentVersion: String = "",
    val cameraSupport: Boolean = false,
    val cameras: List<CameraLens> = emptyList(),
    val displays: List<DisplayInfo> = emptyList(),
) {
    val primaryDisplay: DisplayInfo? get() = displays.firstOrNull()
    val title: String
        get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ")
}

/** 设备当前接入者摘要。普通用户视角仅有 inUse + count，管理员可见具体用户与剩余时间 */
data class DeviceClient(
    val name: String,
    val kind: String,
    val remainingSeconds: Long,
    val count: Int,
)

data class Device(
    val id: String,
    val info: DeviceInfo,
    val online: Boolean,
    val screenState: String,
    val lastSeen: Long?,
    val clientCount: Int,
    val clients: List<DeviceClient>,
    /** 普通用户：自己对该设备的租约剩余秒数；null = 无租约限制 */
    val myLeaseRemainingSeconds: Long?,
    /** 管理员：当前活跃租约持有者 */
    val leaseHolder: String?,
) {
    val displayName: String get() = id
    val inUse: Boolean get() = clientCount > 0
    val screenOff: Boolean get() = screenState.equals("off", ignoreCase = true)
}

data class Tag(val id: String, val name: String, val color: String)

data class TagsPayload(
    val tags: List<Tag>,
    val deviceTags: Map<String, List<String>>,
) {
    companion object {
        val EMPTY = TagsPayload(emptyList(), emptyMap())
    }
}

/** 标签增删改的纯函数集合（无副作用，便于单测）。所有写操作统一走这里，保证本地乐观更新与服务端语义一致。 */
object TagOps {
    /** 新增或按 id 覆盖一个标签 */
    fun upsert(p: TagsPayload, tag: Tag): TagsPayload {
        val exists = p.tags.any { it.id == tag.id }
        val tags = if (exists) p.tags.map { if (it.id == tag.id) tag else it } else p.tags + tag
        return p.copy(tags = tags)
    }

    /** 删除标签定义，并从所有设备映射中移除该标签；映射清空的设备条目一并删除 */
    fun delete(p: TagsPayload, tagId: String): TagsPayload = TagsPayload(
        tags = p.tags.filterNot { it.id == tagId },
        deviceTags = p.deviceTags.mapValues { (_, ids) -> ids - tagId }.filterValues { it.isNotEmpty() },
    )

    /** 设置某设备的标签集合；空集合等价于删除该设备的映射条目 */
    fun setDeviceTags(p: TagsPayload, deviceId: String, tagIds: List<String>): TagsPayload {
        val map = p.deviceTags.toMutableMap()
        if (tagIds.isEmpty()) map.remove(deviceId) else map[deviceId] = tagIds
        return p.copy(deviceTags = map)
    }

    /** 重名判断：trim + 忽略大小写，排除自身 id */
    fun isDuplicateName(tags: List<Tag>, name: String, selfId: String?): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        return tags.any { it.id != selfId && it.name.trim().equals(n, ignoreCase = true) }
    }
}

/** 服务器盘（downloads/ 目录）中的一个文件。服务器中转模式下先上传到这里，再下发到设备。 */
data class ServerFile(
    val name: String,
    val size: Long,
    val updatedAt: Long,
    /** 服务端相对下载路径，如 /downloads/xxx（仅参考，实际下发用签名 URL） */
    val url: String,
    val owner: String,
)

/** 批量任务里单台设备的子任务状态 */
data class SubTaskStatus(
    val deviceId: String,
    val status: String,   // queued / running / success / failed（服务端/agent 约定）
    val progress: Int,
    val result: String,
) {
    val finished: Boolean get() = status.equals("success", true) || status.equals("failed", true) ||
        status.equals("done", true) || status.equals("error", true) || status.equals("completed", true)
    val ok: Boolean get() = status.equals("success", true) || status.equals("done", true) || status.equals("completed", true)
}

/** /api/tasks/details 的解析结果 */
data class TaskDetails(
    val taskId: String,
    val type: String,
    val devices: Map<String, SubTaskStatus>,
)

/** 服务器中转任务类型：apk 走安装，其它走推送到 dest_path */
enum class ServerTaskType(val wire: String) { INSTALL("install"), PUSH_FILE("push_file") }

/** 服务器中转文件下发的纯逻辑助手（便于单测） */
object ServerFileOps {
    /** apk 文件名 → install；否则 push_file */
    fun taskTypeFor(fileName: String): ServerTaskType =
        if (fileName.trim().endsWith(".apk", ignoreCase = true)) ServerTaskType.INSTALL else ServerTaskType.PUSH_FILE

    /** push_file 的默认目标路径：/sdcard/Download/<name> */
    fun defaultDestPath(fileName: String): String = "/sdcard/Download/" + fileName.trim().substringAfterLast('/')

    /** 由服务端返回的相对签名 URL 组装绝对下发 URL（payload），对齐 Web 端 location.protocol+host+url */
    fun absoluteUrl(httpBase: String, signedPath: String): String =
        if (signedPath.startsWith("http://") || signedPath.startsWith("https://")) signedPath
        else httpBase.trimEnd('/') + "/" + signedPath.trimStart('/')
}

data class QuickText(
    val id: String,
    val title: String,
    val content: String,
    val autoEnter: Boolean,
    val createdAt: Long,
)

enum class AccessMode(val wire: String, val label: String) {
    FULL("full", "完全控制"),
    VIEW_ONLY("view_only", "仅观看");

    companion object {
        fun of(wire: String?): AccessMode = entries.firstOrNull { it.wire == wire } ?: FULL
    }
}

data class Share(
    val token: String,
    val cardCode: String,
    val deviceId: String,
    val creator: String,
    val createdAt: Long?,
    /** null = 永久 */
    val expiresAt: Long?,
    val accessMode: AccessMode,
    val requirePassword: Boolean,
    val allowClipboard: Boolean,
    val allowFileTx: Boolean,
    val description: String,
    val useCount: Long,
    val lastUsedAt: Long?,
    val activeConnections: Int,
)

data class ShareCreateRequest(
    val deviceId: String,
    val expireSeconds: Long,
    val accessMode: AccessMode,
    val password: String,
    val description: String,
    val allowClipboard: Boolean,
    val allowFileTx: Boolean,
    val customCardCode: String,
)

data class ShareCreated(
    val token: String,
    val cardCode: String,
    val shareUrl: String,
    val expiresAt: Long?,
)

data class AdminUser(
    val username: String,
    val role: String,
    val note: String,
    val online: Boolean,
    val activeDevices: List<String>,
    val expiresAt: Long?,
    val policy: UserPolicy,
) {
    val isAdmin: Boolean get() = role == "admin"
    fun isExpired(now: Long = System.currentTimeMillis()) = expiresAt != null && expiresAt < now
}

data class AdminUserCreate(
    val username: String,
    val password: String,
    val role: String,
    val note: String,
    val expireSeconds: Long,
)
