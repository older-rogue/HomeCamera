package com.zx.homecamera.ui.activity

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zx.homecamera.ClientListViewModel
import com.zx.homecamera.R
import com.zx.homecamera.core.app.CollectorDevice
import com.zx.homecamera.core.app.ScanStatus
import com.zx.homecamera.ui.AppTopBar
import com.zx.homecamera.ui.ScanRefreshButton
import com.zx.homecamera.ui.StatusDot
import com.zx.homecamera.ui.StatusDotView
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.ui.theme.Teal700

class ClientListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: ClientListViewModel = viewModel()
            val state by viewModel.state.collectAsState()

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
                                        onClick = {
                                            viewModel.stopScan()
                                            openViewer(device)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openViewer(device: CollectorDevice) {
        val intent = Intent(this, ViewerActivity::class.java)
        device.toIntent(intent)
        startActivity(intent)
    }
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
