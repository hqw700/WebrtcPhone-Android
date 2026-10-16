package com.webrtc_phone.android.data.repo

import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.data.api.CloudPhoneApi
import com.webrtc_phone.android.data.model.QuickText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 快捷文本（文本下发模板），按用户存储在服务端，与 Web 端共享 */
class QuickTextRepository(private val api: CloudPhoneApi) {

    private val _items = MutableStateFlow<List<QuickText>>(emptyList())
    val items: StateFlow<List<QuickText>> = _items.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun load(force: Boolean = false) {
        if (loaded && !force) return
        _items.value = api.quickTexts()
        loaded = true
    }

    suspend fun upsert(title: String, content: String, autoEnter: Boolean, id: String? = null) {
        val list = _items.value
        val next = if (id != null && list.any { it.id == id }) {
            list.map { if (it.id == id) it.copy(title = title, content = content, autoEnter = autoEnter) else it }
        } else {
            list + QuickText(
                id = "qt_" + UUID.randomUUID().toString().take(8),
                title = title,
                content = content,
                autoEnter = autoEnter,
                createdAt = System.currentTimeMillis(),
            )
        }
        save(next)
    }

    suspend fun delete(id: String) = save(_items.value.filterNot { it.id == id })

    private suspend fun save(next: List<QuickText>) {
        val old = _items.value
        _items.value = next
        try {
            api.saveQuickTexts(next)
        } catch (e: ApiException) {
            _items.value = old
            throw e
        }
    }
}
