@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.webrtc_phone.android.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.webrtc_phone.android.data.model.Device
import com.webrtc_phone.android.rtc.CameraParams
import com.webrtc_phone.android.rtc.channel.Keys
import com.webrtc_phone.android.ui.home.CameraResolutions
import com.webrtc_phone.android.ui.home.lensOptions
import kotlin.math.roundToInt

/** 虚拟导航键：返回 / 主页 / 多任务（长按主页 = 语音助手，长按返回 = 菜单） */
@Composable
fun NavKeys(vm: StreamViewModel, vertical: Boolean) {
    val items = listOf(
        Triple(Icons.AutoMirrored.Filled.ArrowBack, "返回", Keys.BACK),
        Triple(Icons.Filled.Circle, "主页", Keys.HOME),
        Triple(Icons.Filled.Menu, "多任务", Keys.APP_SWITCH),
    )
    val content: @Composable (Modifier) -> Unit = { m ->
        items.forEach { (icon, label, key) ->
            Box(
                m.combinedClickable(
                    onClick = { vm.press(key) },
                    onLongClick = { vm.longPress(if (key == Keys.BACK) Keys.MENU else key) },
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, label, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(if (key == Keys.HOME) 18.dp else 22.dp))
            }
        }
    }
    if (vertical) Column(Modifier.fillMaxHeight().width(48.dp).background(Color.Black), verticalArrangement = Arrangement.SpaceEvenly) {
        content(Modifier.fillMaxWidth().weight(1f))
    } else Row(Modifier.fillMaxWidth().height(44.dp).background(Color.Black), horizontalArrangement = Arrangement.SpaceEvenly) {
        content(Modifier.fillMaxHeight().weight(1f))
    }
}

/**
 * 左侧边缘把手：常驻显示，点击打开“切换设备”侧边栏。
 * 只做点击不做滑动，避免与云手机自身的边缘返回手势冲突。
 */
@Composable
fun DrawerHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 22.dp, height = 64.dp)
            .background(Color(0xCC1F2633), androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.ChevronRight, "切换设备", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** 可拖动悬浮球：点击展开工具面板，长按打开设备侧边栏，拖动后吸附屏幕左右边缘 */
@Composable
fun FloatingBall(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val ballPx = with(density) { 48.dp.toPx() }
        val maxX = constraints.maxWidth - ballPx
        val maxY = constraints.maxHeight - ballPx
        var x by remember { mutableStateOf(maxX) }
        var y by remember { mutableStateOf(maxY * 0.35f) }
        Box(
            Modifier
                .offset { IntOffset(x.coerceIn(0f, maxX).roundToInt(), y.coerceIn(0f, maxY).roundToInt()) }
                .size(48.dp)
                .shadow(6.dp, CircleShape)
                .background(Color(0xCC1F2633), CircleShape)
                .pointerInputDrag(
                    onDrag = { dx, dy -> x = (x + dx).coerceIn(0f, maxX); y = (y + dy).coerceIn(0f, maxY) },
                    onEnd = { x = if (x + ballPx / 2 < constraints.maxWidth / 2f) 0f else maxX },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick?.let { cb -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); cb() } },
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Apps, "工具", tint = Color.White)
        }
    }
}

private fun Modifier.pointerInputDrag(onDrag: (Float, Float) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
            change.consume()
            onDrag(amount.x, amount.y)
        }
    }

/** 摄像头监控控制条：镜头、分辨率、静音、镜像、抓拍、退出 */
@Composable
fun CameraBar(
    vm: StreamViewModel,
    device: Device?,
    onSnapshot: () -> Unit,
    onExit: () -> Unit,
) {
    val p = vm.cameraParams ?: return
    val lenses = remember(device) { device?.let { lensOptions(it) } ?: lensOptions(fakeDevice()) }
    val chip = FilterChipDefaults.filterChipColors(
        labelColor = Color.White, selectedContainerColor = Color.White, selectedLabelColor = Color.Black,
    )
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.85f)).padding(vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lenses.forEach { o ->
                val sel = o.lens.id == p.cameraId && o.lens.facing == p.facing && o.lens.zoom == p.zoom
                FilterChip(sel, { if (!sel) vm.switchCamera(p.copy(facing = o.lens.facing, cameraId = o.lens.id, zoom = o.lens.zoom)) }, { Text(o.label) }, colors = chip)
            }
            CameraResolutions.forEach { (size, label) ->
                FilterChip(p.size == size, { if (p.size != size) vm.switchCamera(p.copy(size = size)) }, { Text(label) }, colors = chip)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BarIcon(if (vm.audioOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "环境音") { vm.toggleAudio() }
            BarIcon(Icons.Filled.Flip, "镜像") { vm.mirrored = !vm.mirrored }
            BarIcon(Icons.Filled.CameraAlt, "抓拍", big = true, onClick = onSnapshot)
            BarIcon(Icons.Filled.FlipCameraAndroid, "翻转") {
                val front = p.facing == "front"
                vm.switchCamera(p.copy(facing = if (front) "back" else "front", cameraId = if (front) "0" else "1", zoom = 1.0))
            }
            BarIcon(Icons.Filled.Close, "退出", onClick = onExit)
        }
        if (vm.cameraZoom > 1.01f) Text(
            "数字变焦 %.1fx（双指缩放）".format(vm.cameraZoom), color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (big) 60.dp else 48.dp)) {
        Box(
            Modifier.size(if (big) 54.dp else 40.dp).background(if (big) Color.White else Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (big) Color.Black else Color.White) }
    }
}

private fun fakeDevice() = Device("", com.webrtc_phone.android.data.model.DeviceInfo(), true, "", null, 0, emptyList(), null, null)
