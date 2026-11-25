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

    fun playBuiltIn(
        streamUrl: String,
        title: String,
        episodeLabel: String,
        link: String,
        fileId: Int,
        startPositionMs: Long,
        engine: Int = StorageManager.PLAYER_ENGINE_MEDIA3
    ): LaunchResult {
        val resolvedEngine = if (engine == StorageManager.PLAYER_ENGINE_VLC) {
            StorageManager.PLAYER_ENGINE_VLC
        } else {
            StorageManager.PLAYER_ENGINE_MEDIA3
        }
        val intent = Intent(context, BuiltInPlayerActivity::class.java).apply {
            putExtra(BuiltInPlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(BuiltInPlayerActivity.EXTRA_TITLE, title)
            putExtra(BuiltInPlayerActivity.EXTRA_EPISODE_LABEL, episodeLabel)
            putExtra(BuiltInPlayerActivity.EXTRA_LINK, link)
            putExtra(BuiltInPlayerActivity.EXTRA_FILE_ID, fileId)
            putExtra(BuiltInPlayerActivity.EXTRA_START_POS_MS, startPositionMs)
            putExtra(BuiltInPlayerActivity.EXTRA_PLAYER_ENGINE, resolvedEngine)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val label = if (resolvedEngine == StorageManager.PLAYER_ENGINE_VLC) {
            "Встроенный плеер (VLC)"
        } else {
            "Встроенный плеер (Media3)"
        }
        return if (tryLaunch(intent)) {
            LaunchResult(true, label)
        } else {
            LaunchResult(false, error = "Не удалось открыть встроенный плеер")
        }
    }

    fun playExternal(streamUrl: String, title: String): LaunchResult {
        val uri = Uri.parse(streamUrl)

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
