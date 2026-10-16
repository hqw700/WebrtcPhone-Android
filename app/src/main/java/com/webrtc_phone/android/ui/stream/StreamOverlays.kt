package com.webrtc_phone.android.ui.stream

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
fun VideoArea(renderer: SurfaceViewRenderer, ratio: Float, zoom: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Layout(content = {
            AndroidView(factory = { renderer }, modifier = Modifier)
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
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(deviceName, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                (if (retry > 0) "第 $retry 次重连 · " else "") + when (phase) {
                    ConnectionPhase.Signaling -> "连接服务器…"
                    ConnectionPhase.WaitingOffer -> "等待设备响应…"
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
