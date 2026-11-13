package com.webrtc_phone.android.ui.stream

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.webrtc_phone.android.rtc.ConnectionPhase
import org.webrtc.SurfaceViewRenderer

/**
 * 视频区域：按帧宽高比等比居中；摄像头模式下 zoom>1 时放大并由父布局裁剪（数字变焦）。
 * SurfaceView 不支持 scale 变换，只能通过改变自身尺寸实现缩放。
 */
@Composable
fun VideoArea(videoView: android.view.View, ratio: Float, zoom: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Layout(content = {
            AndroidView(factory = { videoView }, modifier = Modifier)
        }) { measurables, c ->
            val maxW = c.maxWidth
            val maxH = c.maxHeight
            var w = maxW
            var h = (maxW / ratio).toInt()
            if (h > maxH) {
                h = maxH
                w = (maxH * ratio).toInt()
            }
            w = (w * zoom).toInt()
            h = (h * zoom).toInt()
            val p = measurables.first().measure(Constraints.fixed(w, h))
            layout(maxW, maxH) { p.place((maxW - w) / 2, (maxH - h) / 2) }
        }
    }
}

@Composable
fun ConnectingOverlay(phase: ConnectionPhase, retry: Int, deviceName: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ConnectionLogo()
            Spacer(Modifier.height(8.dp))
            Text(deviceName, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                (if (retry > 0) "第 $retry 次重连 · " else "") + when (phase) {
                    ConnectionPhase.Signaling -> "连接服务器…"
                    ConnectionPhase.WaitingOffer -> "等待设备响应…"
                    ConnectionPhase.WaitingProjection -> "等待手机用户确认共享屏幕…"
                    ConnectionPhase.Negotiating -> "建立加密通道…"
                    ConnectionPhase.Connected -> "等待首帧画面…"
                    else -> "连接中…"
                },
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onExit) { Text("取消", color = Color.White) }
        }
    }
}

@Composable
private fun ConnectionLogo() {
    val animation = rememberInfiniteTransition(label = "连接动画")
    val progress by animation.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "轮廓成形"
    )
    val cloud = remember {
        PathParser().parsePathString(
            "M284 776C157 776 92 689 92 584c0-111 83-196 193-200 28-139 139-225 269-225 " +
                "151 0 257 116 265 247 106 1 177 83 177 185 0 110-89 185-204 185Z"
        ).toPath()
    }
    val teal = remember {
        PathParser().parsePathString(
            "M762 388c135-17 234 73 234 198 0 117-85 190-201 190H663c-48-40-60-106-47-167 " +
                "14-69 59-185 146-221Z"
        ).toPath()
    }
    val trace = remember { Path() }
    val measure = remember(cloud) { PathMeasure().apply { setPath(cloud, true) } }
    val blue = Color(0xFF1671FF)
    val mint = Color(0xFF12D9BC)
    Canvas(Modifier.size(136.dp)) {
        val reveal = FastOutSlowInEasing.transform(progress)
        val fill = ((reveal - 0.45f) / 0.4f).coerceIn(0f, 1f)
        val unit = size.minDimension / 1024f
        withTransform({ scale(unit, unit, Offset.Zero) }) {
            drawPath(cloud, blue.copy(alpha = 0.16f), style = Stroke(14f))
            trace.reset()
            measure.getSegment(0f, measure.length * reveal, trace)
            drawPath(trace, Brush.linearGradient(listOf(blue, mint), Offset(140f, 0f), Offset(920f, 800f)), style = Stroke(14f))
            drawPath(cloud, blue.copy(alpha = fill))
            drawPath(teal, mint.copy(alpha = fill))
            withTransform({
                translate(0f, 88f * (1f - reveal))
                rotate(-13f * reveal, Offset(520f, 545f))
                scale(0.78f + reveal * 0.22f, 0.66f + reveal * 0.34f, Offset(520f, 545f))
            }) {
                val phone = Offset(350f, 280f)
                val frame = Size(345f, 525f)
                drawRoundRect(Color(0xFFBBDFFF).copy(alpha = fill), phone + Offset(12f, 16f), frame, CornerRadius(63f))
                drawRoundRect(Color.White.copy(alpha = fill), phone, frame, CornerRadius(63f))
                drawRoundRect(Color(0xFFDBEEFF), phone, frame, CornerRadius(63f), style = Stroke(12f))
                drawRoundRect(Color(0xFF125DF5).copy(alpha = fill), Offset(383f, 315f), Size(279f, 445f), CornerRadius(43f))
                val play = Path().apply {
                    moveTo(486f, 480f)
                    lineTo(486f, 596f)
                    lineTo(585f, 538f)
                    close()
                }
                drawPath(play, Color.White.copy(alpha = fill))
            }
        }
    }
}

@Composable
fun FailedOverlay(phase: ConnectionPhase.Failed, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("连接已断开", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(phase.message, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onExit) { Text("退出", color = Color.White) }
                if (!phase.authError) Button(onClick = onRetry) { Text("重新连接") }
            }
        }
    }
}

@Composable
fun Banner(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.9f), contentColor = Color.White, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (color != Color.Gray) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Suppress("unused")
private fun Modifier.ratio(r: Float) = this.aspectRatio(r)
