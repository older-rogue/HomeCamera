package com.zx.homecamera.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zx.homecamera.MainActivity
import com.zx.homecamera.R
import com.zx.homecamera.local.LocalData
import com.zx.homecamera.network.ApiService
import com.zx.homecamera.network.UpdateInfo
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.utils.StatusBarUtil

/**
 * 启动页 Activity
 *
 * 负责检查应用更新，并引导用户进入主页面。
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusBarUtil.setWhiteStatusBar(this)
        setContent {
            HomeCameraTheme {
                SplashScreen(
                    onUpdateAvailable = { updateInfo ->
                        UpdateDialogHolder.show(updateInfo) { navigateToNextScreen() }
                    },
                    onNoUpdate = { navigateToNextScreen() },
                )
            }
        }
    }

    /**
     * 导航到下一个页面
     *
     * 首次启动进入介绍页，否则进入主页面。
     */
    private fun navigateToNextScreen() {
        val intent = if (LocalData.getIsFirst(this)) {
            Intent(this, IntroActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        startActivity(intent)
        finish()
    }
}

/**
 * 启动页界面
 */
@Composable
private fun SplashScreen(
    onUpdateAvailable: (UpdateInfo) -> Unit,
    onNoUpdate: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        var resolved = false
        ApiService.instance.checkUpdate { updateInfo ->
            if (resolved) return@checkUpdate
            resolved = true
            val localVersion = try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } catch (e: Exception) {
                0
            }
            val remoteVersion = updateInfo.buildVersionNo.toIntOrNull() ?: 0
            if (remoteVersion > localVersion) {
                onUpdateAvailable(updateInfo)
            } else {
                onNoUpdate()
            }
        }
        // checkUpdate 静默失败时不回调，兜底直接进入主页面，避免卡在启动页
        kotlinx.coroutines.delay(1500)
        if (!resolved) {
            resolved = true
            onNoUpdate()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "HomeCamera",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
        }

        // 展示更新弹窗
        UpdateDialogHolder.Content {
            // 弹窗跳过后无额外操作，主流程已由 SplashActivity.navigateToNextScreen 处理
        }
    }
}

/**
 * 更新弹窗的持有者
 *
 * 通过 [show] 触发显示，[Content] 负责渲染当前待展示的弹窗。
 */
private object UpdateDialogHolder {

    private val current = mutableStateOf<UpdateInfo?>(null)
    private var dismissCallback: (() -> Unit)? = null

    fun show(updateInfo: UpdateInfo, onSkip: () -> Unit) {
        current.value = updateInfo
        dismissCallback = onSkip
    }

    @Composable
    fun Content(onSkip: () -> Unit) {
        val context = LocalContext.current
        val info by current

        info?.let { updateInfo ->
            AlertDialog(
                onDismissRequest = {
                    current.value = null
                    dismissCallback?.invoke()
                    dismissCallback = null
                    onSkip()
                },
                title = {
                    Text(text = "发现新版本 ${updateInfo.buildVersion}")
                },
                text = {
                    Text(text = updateInfo.buildUpdateDescription)
                },
                confirmButton = {
                    Button(onClick = {
                        val url = updateInfo.appUrl
                        if (url.isNotEmpty()) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                        // 跳转下载后退出应用，等待用户安装新版本
                        (context as? android.app.Activity)?.finish()
                    }) {
                        Text(text = "立即更新")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        current.value = null
                        dismissCallback?.invoke()
                        dismissCallback = null
                        onSkip()
                    }) {
                        Text(text = "暂不更新")
                    }
                },
            )
        }
    }
}
