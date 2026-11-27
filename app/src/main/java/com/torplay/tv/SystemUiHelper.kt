package com.torplay.tv

import android.app.Dialog
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SystemUiHelper {

    fun applyImmersiveFullscreen(window: Window?) {
        if (window == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        }
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            runCatching {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            }
        }
    }

    fun applyImmersiveToDialog(dialog: Dialog) {
        applyImmersiveFullscreen(dialog.window)
    }

    fun applySafeSystemBarPadding(contentView: View) {
        val baseLeft = contentView.paddingLeft
        val baseTop = contentView.paddingTop
        val baseRight = contentView.paddingRight
        val baseBottom = contentView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val statusVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
            val navVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())

            val topInset = if (statusVisible) maxOf(sysBars.top, cutout.top) else cutout.top
            val bottomInset = if (navVisible) sysBars.bottom else 0
            val leftInset = if (navVisible) maxOf(sysBars.left, cutout.left) else cutout.left
            val rightInset = if (navVisible) maxOf(sysBars.right, cutout.right) else cutout.right

            view.setPadding(
                baseLeft + leftInset,
                baseTop + topInset,
                baseRight + rightInset,
                baseBottom + bottomInset
            )
            insets
        }
        ViewCompat.requestApplyInsets(contentView)
    }
}
