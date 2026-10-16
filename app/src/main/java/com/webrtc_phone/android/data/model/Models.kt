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

/** 卡密兑换结果：用于免登录访客直连 */
data class CardRedeemResult(
    val token: String,
    val cardCode: String,
    val deviceId: String,
    val deviceName: String,
    val online: Boolean,
    val accessMode: AccessMode,
    val expiresAt: Long?,
    val remainingSeconds: Long,
    val allowClipboard: Boolean,
    val allowFileTx: Boolean,
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
