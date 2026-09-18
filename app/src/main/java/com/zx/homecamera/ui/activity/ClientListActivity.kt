package com.zx.homecamera.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.ClientListViewModel
import com.zx.homecamera.R
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.ScanStatus
import com.zx.homecamera.local.ClientSavedPasswords
import com.zx.homecamera.network.AuthFailedException
import com.zx.homecamera.network.LanViewerConnector
import com.zx.homecamera.ui.AppTopBar
import com.zx.homecamera.ui.ScanRefreshButton
import com.zx.homecamera.ui.StatusDot
import com.zx.homecamera.ui.StatusDotView
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.ui.theme.Teal700
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClientListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ClientListViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current
            // 待验证密码的设备：非空时弹密码框；null 表示当前无待验证设备。
            var pendingDevice by remember { mutableStateOf<CollectorDevice?>(null) }
            var passwordInput by remember { mutableStateOf("") }
            // 验证中状态：先验证密码再进入播放页，避免跳转后才发现密码错误闪一下。
            var verifying by remember { mutableStateOf(false) }
            var verifyError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            fun openViewer(device: CollectorDevice, password: String) {
                viewModel.stopScan()
                val intent = Intent(this@ClientListActivity, ViewerActivity::class.java)
                device.toIntent(intent)
                intent.putExtra(CollectorDevice.EXTRA_DEVICE_PASSWORD, password)
                startActivity(intent)
            }

            /**
             * 先与采集端做一次轻量握手验证密码，通过后才进入播放页。
             * 密码错误：清除已保存密码并提示重输；网络失败：提示无法连接。
             */
            fun verifyAndOpen(device: CollectorDevice, password: String, fromSaved: Boolean) {
                verifying = true
                verifyError = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            LanViewerConnector().connect(
                                device = device,
                                timeoutMillis = VERIFY_CONNECT_TIMEOUT_MILLIS,
                                password = password,
                            )
                        }
                    }
                    val connection = result.getOrNull()
                    if (connection != null) {
                        connection.close()
                        verifying = false
                        // 验证通过才保存密码，二次进入免输入。
                        ClientSavedPasswords.savePassword(context, device.deviceId, password)
                        pendingDevice = null
                        openViewer(device, password)
                    } else if (result.exceptionOrNull() is AuthFailedException) {
                        verifying = false
                        ClientSavedPasswords.clearPassword(context, device.deviceId)
                        // 已存密码失效：弹出密码框让用户重输；对话框场景直接显示错误。
                        if (!fromSaved) {
                            verifyError = "密码错误，请重试"
                        } else {
                            passwordInput = ""
                            verifyError = "密码错误，请重试"
                            pendingDevice = device
                        }
                    } else {
                        verifying = false
                        if (fromSaved) {
                            Toast.makeText(context, "无法连接采集端", Toast.LENGTH_SHORT).show()
                        } else {
                            verifyError = "无法连接采集端，请重试"
                        }
                    }
                }
            }

            fun onDeviceClicked(device: CollectorDevice) {
                if (verifying) return
                val saved = ClientSavedPasswords.getPassword(context, device.deviceId)
                if (saved != null) {
                    // 有已保存密码：仍先验证（采集端可能已改密），通过后再进入。
                    verifyAndOpen(device, saved, fromSaved = true)
                } else {
                    // 首次进入：要求输入密码，验证通过后由观看页保存。
                    passwordInput = ""
                    verifyError = null
                    pendingDevice = device
                }
            }

            HomeCameraTheme {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    AppTopBar(title = "客户端", showBack = true, onBack = { finish() })
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                StatusDotView(StatusDot.Active)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = state.scanStatus.label(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            ScanRefreshButton(onClick = { viewModel.startScan() })
                        }

                        if (state.devices.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (state.scanStatus == ScanStatus.Scanning) "正在扫描局域网采集端" else "未发现采集端",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(state.devices, key = { it.deviceId }) { device ->
                                    DeviceItem(
                                        device = device,
                                        onClick = { onDeviceClicked(device) },
                                    )
                                }
                            }
                        }
                    }
                }

                pendingDevice?.let { device ->
                    PasswordDialog(
                        deviceName = device.name,
                        password = passwordInput,
                        error = verifyError,
                        verifying = verifying,
                        onPasswordChange = { passwordInput = it },
                        onConfirm = {
                            verifyAndOpen(device, passwordInput.trim(), fromSaved = false)
                        },
                        onDismiss = {
                            if (!verifying) {
                                pendingDevice = null
                                verifyError = null
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** 进入观看前的密码验证握手超时。LAN 上正常几十毫秒，宽松给 2s。 */
        private const val VERIFY_CONNECT_TIMEOUT_MILLIS = 2_000
    }
}

@Composable
private fun PasswordDialog(
    deviceName: String,
    password: String,
    error: String?,
    verifying: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "输入访问密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "设备：$deviceName",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !verifying,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = password.isNotBlank() && !verifying) {
                if (verifying) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("进入观看")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun DeviceItem(device: CollectorDevice, onClick: () -> Unit) {
    val containerColor = if (device.online) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(enabled = device.online, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_collector),
                contentDescription = null,
                tint = if (device.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDotView(if (device.online) StatusDot.Active else StatusDot.Error)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (device.online) "在线" else "离线",
                        color = if (device.online) Teal700 else MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                text = "${device.hostAddress}:${device.tcpPort}",
                modifier = Modifier.padding(top = 3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

private fun ScanStatus.label(): String = when (this) {
    ScanStatus.Idle -> "等待扫描"
    ScanStatus.Scanning -> "扫描中"
    ScanStatus.Finished -> "扫描完成"
    ScanStatus.Error -> "扫描异常"
}
