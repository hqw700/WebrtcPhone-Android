package com.webrtc_phone.android.ui.manage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.core.util.Formatters
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.data.model.ServerFile
import com.webrtc_phone.android.data.model.ServerFileOps
import com.webrtc_phone.android.data.model.ServerTaskType
import com.webrtc_phone.android.data.model.Tag
import com.webrtc_phone.android.data.repo.ServerOp
import com.webrtc_phone.android.ui.common.ConfirmDialog
import com.webrtc_phone.android.ui.common.EmptyState
import com.webrtc_phone.android.ui.common.Pill
import com.webrtc_phone.android.ui.common.StatusDot
import com.webrtc_phone.android.ui.home.parseTagColor
import com.webrtc_phone.android.ui.theme.AppColors

/**
 * 管理页「批量文件分发」（重设计）：
 *  两步向导：① 选文件 ② 选设备；底部常驻操作条显示当前选择并发起下发。
 *  文件与设备各自可搜索/筛选、独立滚动，应对大量文件/设备；任务进度以底部弹层看板呈现。
 *  视觉沿用全局设计系统（卡片、Pill、StatusDot、Brand 主色、large 圆角）。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BatchFilesScreen(vm: BatchFilesViewModel, onBack: () -> Unit) {
    val picked by vm.picked.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    val dispatching by vm.dispatching.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var step by remember { mutableStateOf(0) } // 0=文件 1=设备
    var showBoard by remember { mutableStateOf(false) }
    var destPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(picked) {
        val f = picked
        destPath = if (f != null && ServerFileOps.taskTypeFor(f.name) == ServerTaskType.PUSH_FILE)
            ServerFileOps.defaultDestPath(f.name) else ""
    }
    LaunchedEffect(task) { if (task != null) showBoard = true }

    val isApk = picked?.let { ServerFileOps.taskTypeFor(it.name) == ServerTaskType.INSTALL } ?: false

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("批量文件分发") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    if (task != null) TextButton(onClick = { showBoard = true }) { Text("任务看板") }
                    TextButton(onClick = { vm.refresh() }) { Text("刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ActionBar(
                step = step,
                picked = picked,
                selectedCount = selected.size,
                isApk = isApk,
                dispatching = dispatching,
                onNext = { step = 1 },
                onBackStep = { step = 0 },
                onDispatch = { vm.dispatch(if (isApk) null else destPath.trim()) },
                dispatchEnabled = picked != null && selected.isNotEmpty() && !dispatching && (isApk || destPath.trim().isNotEmpty()),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StepHeader(step, picked != null, selected.size) { step = it }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (targetState > initialState)
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                    else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                },
                label = "step",
                modifier = Modifier.weight(1f),
            ) { s ->
                if (s == 0) FileStep(vm, picked) else DeviceStep(vm, selected, isApk, destPath, onDest = { destPath = it })
            }
        }
    }

    if (showBoard && task != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showBoard = false }, sheetState = sheetState) {
            TaskBoard(vm, onClear = { vm.clearTask(); showBoard = false })
        }
    }
}

// ---------- 顶部步骤指示 ----------

@Composable
private fun StepHeader(step: Int, fileReady: Boolean, deviceCount: Int, onJump: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepPill(1, "选择文件", active = step == 0, done = fileReady && step > 0, onClick = { onJump(0) })
        Box(
            Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp).background(
                if (fileReady) AppColors.Brand.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
            )
        )
        StepPill(2, "选择设备", active = step == 1, done = false, enabled = fileReady, onClick = { if (fileReady) onJump(1) })
    }
}

@Composable
private fun StepPill(index: Int, label: String, active: Boolean, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { active -> AppColors.Brand; done -> AppColors.Online; else -> MaterialTheme.colorScheme.surfaceContainerHigh }
    val fg = if (active || done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
            .background(if (active) AppColors.Brand.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Filled.Check, null, tint = fg, modifier = Modifier.size(15.dp))
            else Text("$index", style = MaterialTheme.typography.labelMedium, color = fg)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) AppColors.Brand else MaterialTheme.colorScheme.onSurface)
    }
}

// ---------- 步骤一：文件 ----------

@Composable
private fun FileStep(vm: BatchFilesViewModel, picked: ServerFile?) {
    val files by vm.files.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val query by vm.fileQuery.collectAsStateWithLifecycle()
    val ops by vm.ops.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ServerFile?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.uploadFrom(it) }
    }
    val uploadingOp = ops.lastOrNull { it.kind == ServerOp.Kind.UPLOAD && it.state == ServerOp.State.ACTIVE }

    Column(Modifier.fillMaxSize()) {
        SearchBox(query, vm::setFileQuery, "搜索文件名")
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(MaterialTheme.shapes.large)
                .clickable { uploadLauncher.launch(arrayOf("*/*")) },
            color = AppColors.Brand.copy(alpha = 0.08f),
            shape = MaterialTheme.shapes.large,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(AppColors.Brand.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.UploadFile, null, tint = AppColors.Brand, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("上传本地文件到服务器", style = MaterialTheme.typography.bodyLarge)
                    Text("支持 APK 与任意文件，上传后可下发到多台设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (uploadingOp != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("上传 ${uploadingOp.name} · ${Formatters.bytes(uploadingOp.loaded)} / ${Formatters.bytes(uploadingOp.total)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { uploadingOp.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        if (loading && files.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        if (files.isEmpty() && !loading) {
            EmptyState(Icons.Outlined.DriveFolderUpload, if (query.isBlank()) "服务器盘暂无文件" else "没有匹配的文件",
                if (query.isBlank()) "点击上方按钮上传第一个文件" else "换个关键字试试")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(files, key = { it.name }) { f ->
                    FileCard(f, selected = picked?.name == f.name, onClick = { vm.pick(f) }, onDelete = { deleting = f })
                }
            }
        }
    }

    deleting?.let { f ->
        ConfirmDialog("删除服务器文件", "从服务器盘删除 ${f.name}？不影响已下发到设备的文件。", "删除", destructive = true,
            onConfirm = { vm.deleteServerFile(f.name) }, onDismiss = { deleting = null })
    }
}

@Composable
private fun FileCard(f: ServerFile, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val isApk = f.name.endsWith(".apk", ignoreCase = true)
    val shape = MaterialTheme.shapes.large
    Surface(
        Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)
            .then(if (selected) Modifier.border(1.5.dp, AppColors.Brand, shape) else Modifier),
        color = if (selected) AppColors.Brand.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        shape = shape,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(
                    (if (isApk) AppColors.Online else MaterialTheme.colorScheme.primary).copy(alpha = 0.12f), RoundedCornerShape(12.dp)
                ), contentAlignment = Alignment.Center
            ) {
                Icon(if (isApk) Icons.Outlined.Android else Icons.Outlined.Description, null,
                    tint = if (isApk) AppColors.Online else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false))
                    if (isApk) { Spacer(Modifier.width(6.dp)); Pill("APK", AppColors.Online) }
                }
                Text(Formatters.bytes(f.size) + (if (f.updatedAt > 0) " · " + Formatters.dateTime(f.updatedAt) else "") + (if (f.owner.isNotBlank()) " · ${f.owner}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Box(Modifier.size(22.dp).background(AppColors.Brand, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            } else {
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------- 步骤二：设备 ----------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DeviceStep(vm: BatchFilesViewModel, selected: Set<String>, isApk: Boolean, destPath: String, onDest: (String) -> Unit) {
    val shown by vm.shownDevices.collectAsStateWithLifecycle()
    val query by vm.devQuery.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val tagFilter by vm.tagFilter.collectAsStateWithLifecycle()
    val tagsP by vm.tags.collectAsStateWithLifecycle()

    val allShownSelected = shown.isNotEmpty() && shown.all { it.id in selected }

    Column(Modifier.fillMaxSize()) {
        SearchBox(query, vm::setDevQuery, "搜索设备 ID / 型号")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeviceScope.entries.forEach { sc ->
                FilterChip(selected = scope == sc, onClick = { vm.setScope(sc) }, label = { Text(sc.label) })
            }
            tagsP.tags.forEach { t ->
                FilterChip(
                    selected = t.id in tagFilter, onClick = { vm.toggleTagFilter(t.id) },
                    label = { Text(t.name) }, leadingIcon = { StatusDot(parseTagColor(t.color)) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("共 ${shown.size} 台 · 已选 ${selected.size}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.toggleSelectAllShown() }, enabled = shown.isNotEmpty()) {
                Text(if (allShownSelected) "取消全选" else "全选当前")
            }
            if (selected.isNotEmpty()) TextButton(onClick = { vm.clearSelection() }) { Text("清空") }
        }
        if (!isApk) {
            OutlinedTextField(destPath, onDest, label = { Text("设备目标路径") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).imePadding())
        }
        if (shown.isEmpty()) {
            EmptyState(Icons.Outlined.CloudOff, "没有符合条件的设备", "调整状态或标签筛选")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { d ->
                    DeviceCheckRow(
                        d,
                        deviceTags = tagsP.deviceTags[d.id].orEmpty().mapNotNull { id -> tagsP.tags.firstOrNull { it.id == id } },
                        checked = d.id in selected,
                        onToggle = { vm.toggleDevice(d.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCheckRow(d: Device, deviceTags: List<Tag>, checked: Boolean, onToggle: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Surface(
        Modifier.fillMaxWidth().clip(shape).clickable(onClick = onToggle)
            .then(if (checked) Modifier.border(1.5.dp, AppColors.Brand, shape) else Modifier),
        color = if (checked) AppColors.Brand.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        shape = shape,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (checked) AppColors.Brand else Color.Transparent)
                    .then(if (!checked) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) { if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            Spacer(Modifier.width(12.dp))
            StatusDot(if (!d.online) AppColors.Offline else if (d.inUse) AppColors.Busy else AppColors.Online)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(d.id, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val sub = buildString {
                    if (d.info.title.isNotBlank()) append(d.info.title)
                    if (!d.online) append(if (isNotEmpty()) " · 离线" else "离线")
                    else if (d.inUse) append(if (isNotEmpty()) " · 使用中" else "使用中")
                }
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            deviceTags.take(2).forEach { Spacer(Modifier.width(4.dp)); Pill(it.name, parseTagColor(it.color)) }
        }
    }
}

// ---------- 底部操作条 ----------

@Composable
private fun ActionBar(
    step: Int,
    picked: ServerFile?,
    selectedCount: Int,
    isApk: Boolean,
    dispatching: Boolean,
    onNext: () -> Unit,
    onBackStep: () -> Unit,
    onDispatch: () -> Unit,
    dispatchEnabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(picked?.name ?: "未选择文件", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (picked != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (step == 0) (if (picked != null) "已选文件 · 下一步选设备" else "先选择要下发的文件")
                    else "已选 $selectedCount 台设备",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            if (step == 0) {
                Button(onClick = onNext, enabled = picked != null) { Text("下一步") }
            } else {
                TextButton(onClick = onBackStep) { Text("上一步") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onDispatch, enabled = dispatchEnabled) {
                    if (dispatching) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp)); Text("下发中")
                    } else Text((if (isApk) "安装" else "下发") + if (selectedCount > 0) " · $selectedCount 台" else "")
                }
            }
        }
    }
}

// ---------- 任务看板（底部弹层） ----------

@Composable
private fun TaskBoard(vm: BatchFilesViewModel, onClear: () -> Unit) {
    val task by vm.task.collectAsStateWithLifecycle()
    val dispatching by vm.dispatching.collectAsStateWithLifecycle()
    val t = task ?: return
    val subs = t.devices.values.toList()
    val success = subs.count { it.ok }
    val failed = subs.count { it.finished && !it.ok }
    val done = subs.count { it.finished }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("任务进度", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!dispatching) TextButton(onClick = onClear) { Text("清除") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("完成 $done/${subs.size}", AppColors.Brand)
            Pill("成功 $success", AppColors.Online, filled = success > 0)
            if (failed > 0) Pill("失败 $failed", AppColors.Danger, filled = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            val total = subs.size.coerceAtLeast(1)
            if (success > 0) Box(Modifier.weight(success.toFloat() / total).fillMaxSize().background(AppColors.Online))
            if (failed > 0) Box(Modifier.weight(failed.toFloat() / total).fillMaxSize().background(AppColors.Danger))
            val rest = total - success - failed
            if (rest > 0) Box(Modifier.weight(rest.toFloat() / total).fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().height(340.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(subs.sortedBy { it.deviceId }, key = { it.deviceId }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(when { s.ok -> AppColors.Online; s.finished -> AppColors.Danger; else -> AppColors.Warning })
                    Spacer(Modifier.width(10.dp))
                    Text(s.deviceId, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            s.ok -> "成功"
                            s.finished -> s.result.ifBlank { "失败" }
                            s.status.equals("running", true) -> "处理中${if (s.progress > 0) " ${s.progress}%" else ""}"
                            else -> "排队中"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.finished && !s.ok) AppColors.Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------- 通用搜索框 ----------

@Composable
private fun SearchBox(value: String, onValue: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value, onValueChange = onValue,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValue("") }) { Icon(Icons.Filled.Close, "清除") } },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
