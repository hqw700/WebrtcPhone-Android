package com.webrtc_phone.android.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webrtc_phone.android.R
import com.webrtc_phone.android.core.net.ServerEndpoint
import com.webrtc_phone.android.ui.common.CertificateTrustDialog
import com.webrtc_phone.android.ui.common.PasswordField
import com.webrtc_phone.android.ui.theme.AppColors

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: LoginViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Box(
        Modifier.fillMaxSize().systemBarsPadding().imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Image(painterResource(R.drawable.ic_app_logo), null, Modifier.size(80.dp))
            Spacer(Modifier.height(12.dp))
            Text("WebRTC 云手机", style = MaterialTheme.typography.headlineSmall)
            Text(
                "低延迟 · 随时随地控制你的云端设备",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            ServerField(vm, s)
            Spacer(Modifier.height(8.dp))

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = s.username,
                onValueChange = { v -> vm.edit { it.copy(username = v) } },
                label = { Text("用户名") },
                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                singleLine = true,
                enabled = !s.loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            PasswordField(
                value = s.password,
                onValueChange = { v -> vm.edit { it.copy(password = v) } },
                label = "密码",
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth(),
                onImeAction = { focus.clearFocus(); vm.login() },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable(enabled = !s.loading) { vm.setRememberPassword(!s.rememberPassword) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = s.rememberPassword, onCheckedChange = vm::setRememberPassword, enabled = !s.loading)
                Text("记住密码", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "加密保存在本机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            s.error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { focus.clearFocus(); vm.login() },
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (s.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("登录")
            }

            Spacer(Modifier.height(16.dp))
            InsecureHint(s.server)
            Spacer(Modifier.height(32.dp))
        }
    }

    s.untrusted?.let { CertificateTrustDialog(it, onTrust = vm::trustCertificate, onDismiss = vm::dismissCertificate) }
}

@Composable
private fun ServerField(vm: LoginViewModel, s: LoginUiState) {
    val focus = LocalFocusManager.current
    var menu by androidx.compose.runtime.remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = s.server,
            onValueChange = { v -> vm.edit { it.copy(server = v.trim()) } },
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.10:8443") },
            leadingIcon = { Icon(Icons.Outlined.Dns, null) },
            trailingIcon = if (s.recent.isNotEmpty()) {
                { IconButton(onClick = { menu = true }, modifier = Modifier.focusProperties { canFocus = false }) { Icon(Icons.Outlined.History, "最近使用") } }
            } else null,
            isError = s.serverError != null,
            supportingText = { Text(s.serverError ?: "默认 HTTPS，端口缺省 8443") },
            singleLine = true,
            enabled = !s.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            s.recent.forEach { r ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(ServerEndpoint.parse(r.url)?.display ?: r.url)
                            if (r.username.isNotBlank()) Text(
                                r.username, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { menu = false; vm.pickRecent(r) },
                    trailingIcon = {
                        IconButton(onClick = { vm.forgetRecent(r); if (s.recent.size <= 1) menu = false }) {
                            Icon(Icons.Filled.Close, "移除", Modifier.size(18.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun InsecureHint(server: String) {
    val ep = ServerEndpoint.parse(server) ?: return
    if (ep.secure) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.LockOpen, null, tint = AppColors.Warning, modifier = Modifier.size(16.dp))
        Text(
            " HTTP 明文连接：密码与画面可能被同网络窃听，建议服务器启用 HTTPS",
            style = MaterialTheme.typography.bodySmall, color = AppColors.Warning
        )
    }
}
