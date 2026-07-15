package com.zx.homecamera.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.zx.homecamera.R

/**
 * 状态栏工具类
 */
object StatusBarUtil {

    /**
     * 设置状态栏为白色，并将状态栏图标设置为深色
     *
     * @param activity 需要设置的 Activity
     */
    fun setWhiteStatusBar(activity: Activity) {
        // 设置状态栏颜色为白色
        activity.window.statusBarColor = ContextCompat.getColor(activity, R.color.white)

        // 在 Android 6.0 及以上系统，设置状态栏图标为深色
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }
}
