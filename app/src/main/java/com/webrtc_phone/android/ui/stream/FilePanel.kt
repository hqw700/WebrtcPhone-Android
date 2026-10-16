package com.webrtc_phone.android.ui.stream

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.rtc.channel.FileTransferClient
import com.webrtc_phone.android.rtc.channel.RemoteFile
import com.webrtc_phone.android.rtc.channel.Transfer
import com.webrtc_phone.android.rtc.channel.TransferKind
import com.webrtc_phone.android.rtc.channel.TransferState
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.theme.AppColors
import kotlinx.coroutines.launch

data class LocalPick(val uri: Uri, val name: String, val size: Long)

fun queryLocal(ctx: Context, uri: Uri): LocalPick {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    // 远端路径安全：去掉路径分隔符，防止目录穿越
    return LocalPick(uri, name.replace('/', '_').replace("..", "_"), size)
}

private val QUICK_DIRS = listOf("/sdcard" to "内部存储", "/sdcard/Download" to "下载", "/sdcard/DCIM" to "相册", "/sdcard/Documents" to "文档", "/data/local/tmp" to "临时")

/** 云手机文件管理器：浏览、上传、下载、新建、删除、APK 安装、传输队列 */
@Composable
fun FilePanel(client: FileTransferClient?, vm: StreamViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/sdcard") }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mkdir by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var downloading by remember { mutableStateOf<RemoteFile?>(null) }
    var showTransfers by remember { mutableStateOf(false) }

    val open by (client?.open ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsStateWithLifecycle()
    val transfers by (client?.transfers ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsStateWithLifecycle()

    fun reload() {
        val c = client ?: return
        scope.launch {
            loading = true
            error = null
            try {
                files = c.list(path)
            } catch (e: Exception) {
                error = e.message ?: "读取失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(client, open, path) { if (open) reload() }
    LaunchedEffect(client) {
        client?.installEvents?.collect { ev ->
            when (ev.status) {
                "installing" -> vm.toast("正在安装…")
                "success" -> vm.toast("安装成功")
                else -> vm.toast("安装失败：${ev.message}")
            }
        }
    }
    // 上传完成后刷新当前目录
    val doneCount = transfers.count { it.kind == TransferKind.UPLOAD && it.state == TransferState.DONE }
    LaunchedEffect(doneCount) { if (doneCount > 0 && open) reload() }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val c = client ?: return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            val p = queryLocal(ctx, uri)
            c.upload(uri, p.name, p.size, path.trimEnd('/') + "/" + p.name)
        }
        if (uris.isNotEmpty()) showTransfers = true
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = downloading
        downloading = null
        if (uri != null && f != null && client != null) {
            client.download(f.path, uri, f.name, f.size)
            showTransfers = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (path == "/" || path.isEmpty()) onClose() else path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "上级") }
            Text(path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "刷新") }
            IconButton(onClick = { mkdir = true }, enabled = open) { Icon(Icons.Filled.CreateNewFolder, "新建文件夹") }
            IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }, enabled = open && vm.target.allowFileTx) { Icon(Icons.Filled.Upload, "上传") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUICK_DIRS.forEach { (p, l) -> AssistChip(onClick = { path = p }, label = { Text(l) }) }
            val active = transfers.count { !it.state.finished }
            if (transfers.isNotEmpty()) AssistChip(onClick = { showTransfers = !showTransfers }, label = { Text(if (active > 0) "传输中 $active" else "传输记录") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (showTransfers && transfers.isNotEmpty()) {
            TransferList(transfers, client)
            HorizontalDivider()
        }
        when {
            client == null || !open -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("文件通道连接中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload() }) { Text("重试") }
                }
            }
            files.isEmpty() && !loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.path }) { f ->
                    FileRow(
                        f,
                        onOpen = { if (f.isDir) path = f.path },
                        onDownload = { if (vm.target.allowFileTx) { downloading = f; downloadLauncher.launch(f.name) } else vm.toast("该分享未开放文件传输") },
                        onInstall = { if (client.installApk(f.path)) vm.toast("已提交安装") },
                        onDelete = { deleting = f },
                    )
                }
            }
        }
    }

    if (mkdir) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { mkdir = false },
            title = { Text("新建文件夹") },
            text = { OutlinedTextField(name, { name = it.replace("/", "") }, singleLine = true, label = { Text("名称") }) },
            confirmButton = {
                Button(enabled = name.isNotBlank() && name != "." && name != "..", onClick = {
                    mkdir = false
                    scope.launch {
                        runCatching { client?.mkdir(path.trimEnd('/') + "/" + name.trim()) }
                            .onFailure { vm.toast(it.message ?: "创建失败") }
                        reload()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { mkdir = false }) { Text("取消") } },
        )
    }
    deleting?.let { f ->
        ConfirmDialog("删除", "删除 ${f.name}${if (f.isDir) " 及其所有内容" else ""}？此操作不可恢复。", "删除", destructive = true,
            onConfirm = {
                scope.launch {
                    runCatching { client?.delete(f.path) }.onFailure { vm.toast(it.message ?: "删除失败") }
                    reload()
                }
            }, onDismiss = { deleting = null })
    }
}

@Composable
private fun FileRow(f: RemoteFile, onOpen: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val isApk = f.name.endsWith(".apk", ignoreCase = true)
    Row(
        Modifier.fillMaxWidth().clickable { if (f.isDir) onOpen() else menu = true }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                f.isDir -> Icons.Outlined.Folder
                isApk -> Icons.Outlined.Android
                f.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif") -> Icons.Outlined.Image
                f.name.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "mov", "3gp") -> Icons.Outlined.Movie
                else -> Icons.Outlined.Description
            },
            null,
            tint = if (f.isDir) AppColors.Warning else if (isApk) AppColors.Online else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (f.isDir) "" else Formatters.bytes(f.size) + " · ") + if (f.modTime > 0) Formatters.dateTime(f.modTime) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
            DropdownMenu(menu, { menu = false }) {
                if (f.isDir) DropdownMenuItem(text = { Text("打开") }, onClick = { menu = false; onOpen() })
                else DropdownMenuItem(text = { Text("下载到本机") }, onClick = { menu = false; onDownload() })
                if (isApk) DropdownMenuItem(text = { Text("安装") }, onClick = { menu = false; onInstall() })
                DropdownMenuItem(text = { Text("删除", color = AppColors.Danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun TransferList(list: List<Transfer>, client: FileTransferClient?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("传输", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (list.any { it.state.finished }) TextButton(onClick = { client?.clearFinished() }) { Text("清除已完成") }
        }
        list.takeLast(6).reversed().forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (t.kind == TransferKind.UPLOAD) "↑ " else "↓ ") + t.name,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium
                    )
                    val status = when (t.state) {
                        TransferState.QUEUED -> "排队中"
                        TransferState.PREPARING -> "校验中 ${Formatters.bytes(t.loaded)}"
                        TransferState.ACTIVE -> "${Formatters.bytes(t.loaded)} / ${Formatters.bytes(t.total)}" + if (t.speed > 0) " · ${Formatters.bytes(t.speed)}/s" else ""
                        TransferState.DONE -> if (t.installAfter) "完成，已提交安装" else "完成"
                        TransferState.FAILED -> "失败：${t.error}"
                        TransferState.CANCELED -> "已取消"
                    }
                    Text(
                        status, style = MaterialTheme.typography.bodySmall,
                        color = if (t.state == TransferState.FAILED) AppColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (t.state == TransferState.ACTIVE || t.state == TransferState.PREPARING) {
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!t.state.finished) IconButton(onClick = { client?.cancel(t.id) }) { Icon(Icons.Filled.Close, "取消") }
            }
        }
    }
}
