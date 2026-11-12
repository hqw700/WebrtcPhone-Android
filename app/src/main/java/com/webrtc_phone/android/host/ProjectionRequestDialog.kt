package com.webrtc_phone.android.host

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.ui.common.CertificateTrustDialog

@Composable
internal fun ProjectionRequestDialog() {
    val context = LocalContext.current
    val pending by ProjectionStandbyService.request.collectAsStateWithLifecycle()
    val certificate by ProjectionStandbyService.certificate.collectAsStateWithLifecycle()
    val touchReady by ProjectionAccessibilityService.connected.collectAsStateWithLifecycle()
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestTicket = ticket
        ticket = null
        if (requestTicket != null) {
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProjectionHostService.start(context, requestTicket, data)
            } else {
                ProjectionStandbyService.reject(requestTicket)
            }
        }
    }
    certificate?.let { info ->
        CertificateTrustDialog(
            info = info,
            onTrust = { ProjectionStandbyService.trustCertificate(context, info) },
            onDismiss = { ProjectionStandbyService.declineCertificate(context, info) }
        )
    }
    val request = pending ?: return
    if (ticket != null || request.authorizing) return
    AlertDialog(
        onDismissRequest = { ProjectionStandbyService.reject(request.ticket) },
        title = { Text("远程协助请求") },
        text = {
            Column {
                Text(if (request.control) "${request.requester} 请求查看并控制屏幕" else "${request.requester} 请求查看屏幕")
                if (request.control && !touchReady) {
                    Text("无障碍触控尚未开启，本次可先共享画面。")
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("打开无障碍设置")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ProjectionStandbyService.authorize(request.ticket)) {
                    ticket = request.ticket
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    val intent = if (Build.VERSION.SDK_INT >= 34) {
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    } else {
                        manager.createScreenCaptureIntent()
                    }
                    runCatching { consent.launch(intent) }.onFailure {
                        ticket = null
                        ProjectionStandbyService.reject(request.ticket)
                    }
                }
            }) { Text("同意") }
        },
        dismissButton = {
            TextButton(onClick = { ProjectionStandbyService.reject(request.ticket) }) { Text("拒绝") }
        }
    )
}
