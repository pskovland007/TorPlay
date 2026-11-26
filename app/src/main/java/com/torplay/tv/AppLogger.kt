package com.torplay.tv

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val MAX_LINES = 250
    private const val LOG_FILE_NAME = "torplay-events.log"

    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private var logFile: File? = null
    private var initialized = false

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val f = File(context.applicationContext.filesDir, LOG_FILE_NAME)
            logFile = f
            runCatching {
                if (f.exists()) {
                    f.readLines(Charsets.UTF_8).takeLast(MAX_LINES).forEach { line ->
                        if (line.isNotBlank()) entries.addLast(line)
                    }
                }
            }
        }
        i("App", "Запуск TorPlay ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    }

    fun i(tag: String, message: String) {
        append("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        append("WARN", tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val full = if (error != null) {
            val errDetail = error.message?.take(160).orEmpty()
            "$message | ${error.javaClass.simpleName}: $errDetail"
        } else {
            message
        }
        append("ERROR", tag, full)
    }

    private fun append(level: String, tag: String, message: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val cleanMsg = message.replace('\n', ' ').replace('\r', ' ').trim()
        val line = "[$ts] [$level] [$tag] $cleanMsg"
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_LINES) {
                entries.removeFirst()
            }
            val f = logFile ?: return
            runCatching {
                f.writeText(entries.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        }
    }

    fun buildDiagnosticReport(storage: StorageManager): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val profileName = when (storage.bufferProfile) {
            StorageManager.BUFFER_PROFILE_FAST -> "Быстрый старт"
            StorageManager.BUFFER_PROFILE_4K -> "Тяжёлый 4K"
            else -> "Стандарт"
        }
        val linesSnapshot = synchronized(lock) { entries.toList() }

        return buildString {
            append("=== TorPlay Diagnostic Log ===\n")
            append("Generated: ").append(ts).append('\n')
            append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            val engineLabel = when (storage.playerEngine) {
                StorageManager.PLAYER_ENGINE_VLC -> "Built-in LibVLC"
                StorageManager.PLAYER_ENGINE_EXTERNAL -> "External MX"
                else -> "Built-in Media3"
            }
            append("Player: ").append(engineLabel).append('\n')
            append("Buffer profile: ").append(profileName).append('\n')
            append("TorrServe URL: ").append(storage.torrServeUrl).append('\n')
            append("Public JacRed: ").append(storage.usePublicJacRed)
                .append(" | Rutor fallback: ").append(storage.useRutorFallback).append('\n')
            append("Custom parser configured: ").append(storage.customParserUrl.isNotBlank()).append('\n')
            append("Network bypass: DoH=").append(storage.useDohDns)
                .append(" | DPI-TLS=").append(storage.useDpiBypass)
                .append(" | Proxy=").append(if (storage.networkProxyUrl.isNotBlank()) "set" else "none").append('\n')
            append("Player HUD: stats=").append(storage.showPlayerStatsHud)
                .append(" | pixelShift=").append(storage.plasmaPixelShift)
                .append(" | corner=").append(storage.playerStatsCorner).append('\n')
            append("==============================\n\n")
            if (linesSnapshot.isEmpty()) {
                append("(Записей пока нет)\n")
            } else {
                for (line in linesSnapshot) {
                    append(line).append('\n')
                }
            }
        }
    }
}
