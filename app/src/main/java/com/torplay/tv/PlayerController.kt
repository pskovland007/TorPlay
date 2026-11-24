package com.torplay.tv

import android.content.Context
import android.content.Intent
import android.net.Uri

class PlayerController(private val context: Context) {

    data class LaunchResult(
        val launched: Boolean,
        val playerName: String = "",
        val error: String? = null
    )

    fun playExternal(streamUrl: String, title: String): LaunchResult {
        val uri = Uri.parse(streamUrl)

        // 1. Priority: MX Player (Pro -> Free -> TV)
        for ((pkg, label) in MX_PACKAGES) {
            if (isPackageInstalled(pkg)) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    setPackage(pkg)
                    putExtra("title", title)
                    putExtra("return_result", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryLaunch(intent)) {
                    return LaunchResult(true, label)
                }
            }
        }

        // 2. Fallback: other popular Android TV players
        for ((pkg, label) in FALLBACK_PLAYERS) {
            if (isPackageInstalled(pkg)) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    setPackage(pkg)
                    putExtra("title", title)
                    putExtra("forcename", title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryLaunch(intent)) {
                    return LaunchResult(true, label)
                }
            }
        }

        // 3. System chooser fallback
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                putExtra("title", title)
            },
            "Выберите видеоплеер"
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return if (tryLaunch(chooser)) {
            LaunchResult(true, "Внешний плеер")
        } else {
            LaunchResult(false, error = "Не найден видеоплеер (установите MX Player)")
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }

    private fun tryLaunch(intent: Intent): Boolean {
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    companion object {
        private val MX_PACKAGES = listOf(
            "com.mxtech.videoplayer.pro" to "MX Player Pro",
            "com.mxtech.videoplayer.ad" to "MX Player",
            "com.mxtech.videoplayer.tv" to "MX Player TV"
        )

        private val FALLBACK_PLAYERS = listOf(
            "net.gtvbox.videoplayer" to "Vimu Player",
            "org.courville.nova" to "Nova Video Player",
            "org.videolan.vlc" to "VLC"
        )
    }
}
