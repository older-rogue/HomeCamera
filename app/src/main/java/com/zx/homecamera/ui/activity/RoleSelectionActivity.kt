package com.zx.homecamera.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zx.homecamera.R
import com.zx.homecamera.core.app.AppRole
import com.zx.homecamera.service.CollectorForegroundService
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.ui.AppTopBar
import com.zx.homecamera.ui.RoleCard

class RoleSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeCameraTheme {
                RoleSelectionScreen(
                    onSelectRole = { role ->
                        when (role) {
                            AppRole.Collector -> {
                                startActivity(Intent(this, CollectorActivity::class.java))
                            }
                            AppRole.Client -> {
                                // 兜底：若上一次采集服务因异常路径未被停止（如系统返回键直接
                                // finish 采集端），进入客户端前主动发 STOP，走服务的正常停止
                                // 流程释放相机/麦克风/编码器，避免后台抢 CPU 导致客户端黑屏。
                                startService(
                                    Intent(this, CollectorForegroundService::class.java)
                                        .setAction(CollectorForegroundService.ACTION_STOP),
                                )
                                startActivity(Intent(this, ClientListActivity::class.java))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RoleSelectionScreen(onSelectRole: (AppRole) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        AppTopBar(title = "HomeCamera", showBack = false, onBack = {})
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "选择运行模式",
                modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            RoleCard(
                iconRes = R.drawable.ic_collector,
                name = "采集端",
                sub = "实时编码 H.264/AAC 推流",
                primary = false,
                onClick = { onSelectRole(AppRole.Collector) },
            )
            RoleCard(
                iconRes = R.drawable.ic_client,
                name = "客户端",
                sub = "查看局域网采集端画面",
                primary = false,
                onClick = { onSelectRole(AppRole.Client) },
            )
        }
    }
}
