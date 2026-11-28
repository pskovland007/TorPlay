package com.torplay.tv

import android.app.Activity
import android.os.Build
import android.view.Display
import java.util.Locale
import kotlin.math.abs

class AutoFrameRateHelper {

    private var originalModeId: Int = 0
    private var appliedModeId: Int = 0
    private var lastAppliedFps: Float = 0f

    fun applyFrameRate(activity: Activity, videoFps: Float, enabled: Boolean): Float? {
        if (!enabled || videoFps < 10f || videoFps > 125f) return null
        if (activity.isFinishing || activity.isDestroyed) return null
        if (lastAppliedFps > 0f && abs(videoFps - lastAppliedFps) < 0.05f) return null

        val display = currentDisplay(activity) ?: return null
        val currentMode = display.mode ?: return null
        val supported = display.supportedModes ?: return null
        if (supported.size <= 1) return null

        if (originalModeId == 0) {
            originalModeId = currentMode.modeId
        }

        val sameResModes = supported.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        if (sameResModes.size <= 1) return null

        val bestMode = selectBestMode(sameResModes, videoFps) ?: return null
        lastAppliedFps = videoFps

        if (bestMode.modeId == currentMode.modeId || bestMode.modeId == appliedModeId) {
            return null
        }

        return runCatching {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = bestMode.modeId
            activity.window.attributes = lp
            appliedModeId = bestMode.modeId
            AppLogger.i(
                "AFR",
                String.format(
                    Locale.US,
                    "Видео %.3f fps -> экран %.2f Гц (%dx%d)",
                    videoFps,
                    bestMode.refreshRate,
                    bestMode.physicalWidth,
                    bestMode.physicalHeight
                )
            )
            bestMode.refreshRate
        }.getOrNull()
    }

    fun resetForNewVideo() {
        lastAppliedFps = 0f
    }

    fun restoreOriginalMode(activity: Activity) {
        if (appliedModeId == 0) return
        val restoreId = originalModeId
        appliedModeId = 0
        lastAppliedFps = 0f
        runCatching {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = restoreId
            activity.window.attributes = lp
            AppLogger.i("AFR", "Восстановлена исходная частота экрана (modeId=$restoreId)")
        }
    }

    private fun currentDisplay(activity: Activity): Display? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
        }.getOrNull()
    }

    private fun selectBestMode(modes: List<Display.Mode>, fps: Float): Display.Mode? {
        val exact = modes
            .filter { abs(it.refreshRate - fps) <= 0.15f }
            .minByOrNull { abs(it.refreshRate - fps) }
        if (exact != null) return exact

        var bestMultiple: Display.Mode? = null
        var bestScore = Float.MAX_VALUE
        for (mode in modes) {
            val rate = mode.refreshRate
            if (rate < fps - 0.15f) continue
            for (mult in 2..5) {
                val target = fps * mult
                val diff = abs(rate - target)
                if (diff <= 0.25f) {
                    val score = diff * 10f + mult * 0.05f
                    if (score < bestScore) {
                        bestScore = score
                        bestMultiple = mode
                    }
                }
            }
        }
        return bestMultiple
    }
}
