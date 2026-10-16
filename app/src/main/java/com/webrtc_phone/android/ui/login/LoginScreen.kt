package com.webrtc_phone.android.ui.login

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
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

            SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("账号登录") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("卡密连接") })
            }
            Spacer(Modifier.height(16.dp))

            if (tab == 0) {
                OutlinedTextField(
                    value = s.username,
                    onValueChange = { v -> vm.edit { it.copy(username = v) } },
                    label = { Text("用户名") },
                    leadingIcon = { Icon(Icons.Outlined.Person, null) },
                    singleLine = true,
                    enabled = !s.loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
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
            } else {
                OutlinedTextField(
                    value = s.cardCode,
                    onValueChange = { v -> vm.edit { it.copy(cardCode = v.uppercase().filter { ch -> !ch.isWhitespace() }) } },
                    label = { Text("卡密") },
                    leadingIcon = { Icon(Icons.Outlined.Key, null) },
                    singleLine = true,
                    enabled = !s.loading,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = if (s.cardNeedsPassword) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.redeemCard() }),
                    supportingText = { Text("无需账号，使用管理员分享的卡密直接连接设备") },
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(s.cardNeedsPassword) {
                    PasswordField(
                        value = s.cardPassword,
                        onValueChange = { v -> vm.edit { it.copy(cardPassword = v) } },
                        label = "访问密码",
                        enabled = !s.loading,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onImeAction = { focus.clearFocus(); vm.redeemCard() },
                    )
                }
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
                onClick = { focus.clearFocus(); if (tab == 0) vm.login() else vm.redeemCard() },
                enabled = !s.loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (s.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (tab == 0) "登录" else "连接设备")
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
