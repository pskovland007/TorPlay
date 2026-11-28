package com.torplay.tv

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MaintenanceManager(private val context: Context) {

    data class OpResult(
        val ok: Boolean,
        val message: String,
        val savedPath: String = ""
    )

    fun needsLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return false
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED
    }

    fun exportBackup(storage: StorageManager, playerPrefs: PlayerPrefs): OpResult {
        return runCatching {
            val root = JSONObject().apply {
                put("format", "torplay_backup_v1")
                put("appVersion", BuildConfig.VERSION_NAME)
                put("exportedAt", System.currentTimeMillis())
                put("storage", storage.exportStateJson())
                put(
                    "player",
                    JSONObject().apply {
                        put("resizeModeIndex", playerPrefs.resizeModeIndex)
                        put("autoNextEpisode", playerPrefs.autoNextEpisode)
                        put("preferredAudioLang", playerPrefs.preferredAudioLang)
                        put("preferredAudioLabel", playerPrefs.preferredAudioLabel)
                        put("subtitlesDisabled", playerPrefs.subtitlesDisabled)
                        put("preferredSubLang", playerPrefs.preferredSubLang)
                        put("preferredSubLabel", playerPrefs.preferredSubLabel)
                        put("subtitleSizeIndex", playerPrefs.subtitleSizeIndex)
                        put("subtitleBgIndex", playerPrefs.subtitleBgIndex)
                    }
                )
            }
            val json = root.toString(2)
            // Always keep an app-external mirror for reliable restore
            saveToAppExternalDir(BACKUP_FILE_NAME, json)
            val savedPath = writeTextToDownloads(BACKUP_FILE_NAME, "application/json", json, overwrite = true)
            AppLogger.i("Backup", "Экспорт бэкапа: $savedPath")
            OpResult(
                ok = true,
                message = "Бэкап сохранён: $savedPath",
                savedPath = savedPath
            )
        }.getOrElse { err ->
            AppLogger.e("Backup", "Ошибка экспорта бэкапа", err)
            OpResult(false, "Ошибка экспорта: ${err.message ?: "нет доступа"}")
        }
    }

    fun importBackup(storage: StorageManager, playerPrefs: PlayerPrefs): OpResult {
        return runCatching {
            val loaded = readNewestBackupText()
                ?: return OpResult(
                    ok = false,
                    message = "Файл $BACKUP_FILE_NAME не найден в Download/TorPlay"
                )
            val (sourceLabel, rawJson) = loaded
            val root = JSONObject(rawJson)
            val storageObj = root.optJSONObject("storage")
                ?: return OpResult(false, "Неверный формат файла бэкапа")

            storage.importStateJson(storageObj)

            root.optJSONObject("player")?.let { p ->
                if (p.has("resizeModeIndex")) playerPrefs.resizeModeIndex = p.optInt("resizeModeIndex", 0)
                if (p.has("autoNextEpisode")) playerPrefs.autoNextEpisode = p.optBoolean("autoNextEpisode", true)
                if (p.has("preferredAudioLang")) playerPrefs.preferredAudioLang = p.optString("preferredAudioLang", "rus")
                if (p.has("preferredAudioLabel")) playerPrefs.preferredAudioLabel = p.optString("preferredAudioLabel", "")
                if (p.has("subtitlesDisabled")) playerPrefs.subtitlesDisabled = p.optBoolean("subtitlesDisabled", false)
                if (p.has("preferredSubLang")) playerPrefs.preferredSubLang = p.optString("preferredSubLang", "rus")
                if (p.has("preferredSubLabel")) playerPrefs.preferredSubLabel = p.optString("preferredSubLabel", "")
                if (p.has("subtitleSizeIndex")) playerPrefs.subtitleSizeIndex = p.optInt("subtitleSizeIndex", PlayerPrefs.SUB_SIZE_NORMAL)
                if (p.has("subtitleBgIndex")) playerPrefs.subtitleBgIndex = p.optInt("subtitleBgIndex", PlayerPrefs.SUB_BG_TRANSLUCENT)
            }

            val favCount = storage.getFavorites().size
            val histCount = storage.getWatchHistory().size
            AppLogger.i("Backup", "Импорт бэкапа из $sourceLabel (избр: $favCount, ист: $histCount)")
            OpResult(
                ok = true,
                message = "Восстановлено из $sourceLabel (избранное: $favCount, история: $histCount)",
                savedPath = sourceLabel
            )
        }.getOrElse { err ->
            AppLogger.e("Backup", "Ошибка импорта бэкапа", err)
            OpResult(false, "Ошибка импорта: ${err.message ?: "повреждён файл"}")
        }
    }

    fun exportLogs(storage: StorageManager): OpResult {
        return runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val fileName = "torplay-log-$stamp.txt"
            val report = AppLogger.buildDiagnosticReport(storage)
            val savedPath = writeTextToDownloads(fileName, "text/plain", report, overwrite = false)
            AppLogger.i("Logs", "Лог сохранён: $savedPath")
            OpResult(
                ok = true,
                message = "Лог сохранён: $savedPath",
                savedPath = savedPath
            )
        }.getOrElse { err ->
            AppLogger.e("Logs", "Ошибка сохранения лога", err)
            OpResult(false, "Ошибка сохранения лога: ${err.message ?: "нет доступа"}")
        }
    }

    private fun writeTextToDownloads(
        fileName: String,
        mimeType: String,
        content: String,
        overwrite: Boolean
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relPath = Environment.DIRECTORY_DOWNLOADS + "/TorPlay/"
            if (overwrite) {
                runCatching {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                        arrayOf(fileName, relPath)
                    )
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                return "Download/TorPlay/$fileName"
            }
        }

        val canWritePublic = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

        if (canWritePublic) {
            @Suppress("DEPRECATION")
            val pubDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TorPlay"
            )
            if (pubDir.exists() || pubDir.mkdirs()) {
                val outFile = File(pubDir, fileName)
                outFile.writeText(content, Charsets.UTF_8)
                return "Download/TorPlay/$fileName"
            }
        }

        val fallbackFile = saveToAppExternalDir(fileName, content)
        return fallbackFile.absolutePath
    }

    private fun saveToAppExternalDir(fileName: String, content: String): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val dir = File(baseDir, "TorPlay").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun readNewestBackupText(): Pair<String, String>? {
        val candidates = mutableListOf<Triple<Long, String, () -> String?>>()

        // 1. Direct filesystem locations (Android 6-9 public Download + app-external mirror)
        val filePaths = mutableListOf<Pair<String, File>>()
        @Suppress("DEPRECATION")
        runCatching {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            filePaths.add("Download/TorPlay/$BACKUP_FILE_NAME" to File(File(pubDownloads, "TorPlay"), BACKUP_FILE_NAME))
            filePaths.add("Download/$BACKUP_FILE_NAME" to File(pubDownloads, BACKUP_FILE_NAME))
        }
        val appExt = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExt != null) {
            filePaths.add("TorPlay/$BACKUP_FILE_NAME" to File(File(appExt, "TorPlay"), BACKUP_FILE_NAME))
        }
        filePaths.add("files/$BACKUP_FILE_NAME" to File(File(context.filesDir, "TorPlay"), BACKUP_FILE_NAME))

        for ((label, f) in filePaths) {
            if (f.exists() && f.isFile && f.canRead() && f.length() > 0L) {
                candidates.add(Triple(f.lastModified(), label) { runCatching { f.readText(Charsets.UTF_8) }.getOrNull() })
            }
        }

        // 2. MediaStore.Downloads on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(BACKUP_FILE_NAME),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        val modifiedMs = cursor.getLong(1) * 1000L
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id
                        )
                        candidates.add(Triple(modifiedMs, "Download/TorPlay/$BACKUP_FILE_NAME") {
                            runCatching {
                                resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            }.getOrNull()
                        })
                    }
                }
            }
        }

        val sorted = candidates.sortedByDescending { it.first }
        for (item in sorted) {
            val text = item.third()
            if (!text.isNullOrBlank()) {
                return item.second to text
            }
        }
        return null
    }

    companion object {
        const val BACKUP_FILE_NAME = "torplay-backup.json"
    }
}
