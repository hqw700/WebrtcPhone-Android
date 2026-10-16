package com.webrtc_phone.android.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.net.ApiException
import com.webrtc_phone.android.data.model.QuickText
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.repo.DeviceRepository
import com.webrtc_phone.android.data.repo.QuickTextRepository
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.home.parseTagColor
import com.webrtc_phone.android.ui.settings.SubScreen
import kotlinx.coroutines.launch

val TagPalette = listOf("#3B82F6", "#22C55E", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899", "#14B8A6", "#64748B")

@Composable
fun TagsScreen(repo: DeviceRepository, onBack: () -> Unit) {
    val tags by repo.tags.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Tag?>(null) }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    LaunchedEffect(Unit) { repo.loadMeta() }

    fun run(msg: String, block: suspend () -> Unit) = scope.launch {
        try {
            block(); snackbar.showSnackbar(msg)
        } catch (e: ApiException) {
            snackbar.showSnackbar(e.message ?: "操作失败")
        }
    }

    SubScreen(
        "标签管理", onBack,
        floating = {
            ExtendedFloatingActionButton(onClick = { editing = Tag(DeviceRepository.newTagId(), "", TagPalette[tags.tags.size % TagPalette.size]) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("新建标签") })
        },
        snackbar = { SnackbarHost(snackbar) },
    ) { pad ->
        if (tags.tags.isEmpty()) EmptyState(Icons.Outlined.Label, "还没有标签", "用标签对设备分组，首页可按标签快速筛选", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tags.tags, key = { it.id }) { t ->
                val count = tags.deviceTags.count { (_, ids) -> t.id in ids }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(parseTagColor(t.color), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall)
                            Text("$count 台设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = t }) { Icon(Icons.Outlined.Edit, "编辑") }
                        IconButton(onClick = { deleting = t }) { Icon(Icons.Outlined.Delete, "删除") }
                    }
                }
            }
        }
    }

    editing?.let { t ->
        TagEditDialog(t, onDismiss = { editing = null }) { nt -> editing = null; run("已保存") { repo.upsertTag(nt) } }
    }
    deleting?.let { t ->
        ConfirmDialog("删除标签", "删除「${t.name}」？设备不会被删除。", "删除", destructive = true,
            onConfirm = { run("已删除") { repo.deleteTag(t.id) } }, onDismiss = { deleting = null })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagEditDialog(tag: Tag, onDismiss: () -> Unit, onSave: (Tag) -> Unit) {
    var name by remember { mutableStateOf(tag.name) }
    var color by remember { mutableStateOf(tag.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tag.name.isEmpty()) "新建标签" else "编辑标签") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TagPalette.forEach { c ->
                        Box(
                            Modifier.size(32.dp).background(parseTagColor(c), CircleShape)
                                .then(if (c.equals(color, true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(tag.copy(name = name.trim(), color = color)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun QuickTextsScreen(repo: QuickTextRepository, onBack: () -> Unit) {
    val items by repo.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<QuickText?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<QuickText?>(null) }

    fun run(msg: String?, block: suspend () -> Unit) = scope.launch {
        try {
            block(); msg?.let { snackbar.showSnackbar(it) }
        } catch (e: ApiException) {
            snackbar.showSnackbar(e.message ?: "操作失败")
        }
    }
    LaunchedEffect(Unit) { run(null) { repo.load(force = true) } }

    SubScreen(
        "快捷文本", onBack,
        floating = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("新建") }) },
        snackbar = { SnackbarHost(snackbar) },
    ) { pad ->
        if (items.isEmpty()) EmptyState(Icons.AutoMirrored.Outlined.ShortText, "暂无快捷文本", "投屏时在「文本下发」中一键发送到云手机", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { q ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.title.ifBlank { "未命名" }, style = MaterialTheme.typography.titleSmall)
                            Text(q.content, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (q.autoEnter) Text("发送后自动回车", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { editing = q }) { Icon(Icons.Outlined.Edit, "编辑") }
                        IconButton(onClick = { deleting = q }) { Icon(Icons.Outlined.Delete, "删除") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        QuickTextDialog(editing, onDismiss = { creating = false; editing = null }) { t, c, a ->
            val id = editing?.id
            creating = false; editing = null
            run("已保存") { repo.upsert(t, c, a, id) }
        }
    }
    deleting?.let { q ->
        ConfirmDialog("删除快捷文本", "删除「${q.title}」？", "删除", destructive = true,
            onConfirm = { run("已删除") { repo.delete(q.id) } }, onDismiss = { deleting = null })
    }
}

@Composable
fun QuickTextDialog(initial: QuickText?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var enter by remember { mutableStateOf(initial?.autoEnter ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建快捷文本" else "编辑快捷文本") },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(40) }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it.take(4000) }, label = { Text("内容") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("发送后自动回车", Modifier.weight(1f))
                    Switch(enter, { enter = it })
                }
            }
        },
        confirmButton = { Button(enabled = content.isNotEmpty(), onClick = { onSave(title.trim().ifBlank { content.take(12) }, content, enter) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
