package com.zx.homecamera.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zx.homecamera.MainActivity
import com.zx.homecamera.local.LocalData
import com.zx.homecamera.ui.theme.HomeCameraTheme
import com.zx.homecamera.ui.theme.Purple40
import com.zx.homecamera.utils.StatusBarUtil

/**
 * 介绍页 Activity
 *
 * 首次启动时展示应用简介与使用须知，用户同意后方可进入主页面。
 */
class IntroActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusBarUtil.setWhiteStatusBar(this)
        setContent {
            HomeCameraTheme {
                IntroScreen(
                    onAgree = {
                        LocalData.setIsFirst(this)
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onDisagree = {
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun IntroScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题
            Text(
                text = "请大家认真阅读",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier
                    .padding(top = 28.dp, bottom = 6.dp)
                    .align(Alignment.CenterHorizontally),
            )

            // 可滚动的内容区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                introParagraphs.forEachIndexed { index, paragraph ->
                    val annotated = buildAnnotatedString {
                        val url = "https://github.com/older-rogue/HomeCamera"
                        val pos = paragraph.indexOf(url)
                        if (pos >= 0) {
                            append(paragraph.substring(0, pos))
                            withStyle(SpanStyle(color = Purple40)) {
                                append(url)
                            }
                            append(paragraph.substring(pos + url.length))
                        } else {
                            append(paragraph)
                        }
                    }
                    Text(
                        text = annotated,
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                        color = Color.Black,
                        modifier = Modifier.padding(top = if (index == 0) 6.dp else 14.dp),
                    )
                }
            }

            // 分割线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color(0xFFCCCCCC)),
            )

            // 底部按钮
            Row(modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
            ) {
                TextButton(
                    onClick = onDisagree,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    Text(
                        text = "不同意",
                        color = Color.Black,
                        fontSize = 15.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .fillMaxHeight()
                        .background(Color(0xFFCCCCCC)),
                )
                TextButton(
                    onClick = onAgree,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    Text(
                        text = "同意并继续",
                        color = Purple40,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

/** 介绍页文案 */
private val introParagraphs = listOf(
    "HomeCamera 是一款局域网家庭摄像头应用，可将闲置手机变为采集端，在另一台设备上实时查看画面与声音。",
    "采集端通过摄像头与麦克风实时编码 H.264 视频与 AAC 音频，经局域网传输至观看端，支持本地录制与分段存储。",
    "所有数据均在局域网内传输，不经过外部服务器，请确保两台设备处于同一 Wi-Fi 网络下使用。",
    "项目完全开源，代码地址：https://github.com/older-rogue/HomeCamera",
    "使用本应用即代表你已知晓并同意上述说明，请合法合规地使用本应用。",
)
