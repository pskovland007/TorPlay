package com.torplay.tv

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {

    data class ReleaseInfo(
        val tagName: String,
        val title: String,
        val apkUrl: String,
        val apkName: String,
        val body: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/pskovland007/TorPlay/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TorPlay-TV")
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@withContext null
                val obj = JSONObject(raw)
                val tag = obj.optString("tag_name").trim()
                val name = obj.optString("name").trim().ifBlank { tag }
                val body = obj.optString("body").trim()

                var apkUrl = ""
                var apkName = "TorPlay-update.apk"
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val assetName = asset.optString("name")
                        val dlUrl = asset.optString("browser_download_url")
                        if (assetName.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                            apkUrl = dlUrl
                            apkName = assetName
                            break
                        }
                    }
                }
                if (apkUrl.isBlank() || tag.isBlank()) return@withContext null
                ReleaseInfo(
                    tagName = tag,
                    title = name,
                    apkUrl = apkUrl,
                    apkName = apkName,
                    body = body
                )
            }
        }.getOrNull()
    }

    fun promptAndInstall(activityContext: Context, release: ReleaseInfo, currentVersionName: String) {
        val msg = buildString {
            append("Текущая версия: ").append(currentVersionName).append("\n")
            append("Релиз на GitHub: ").append(release.title).append(" (").append(release.tagName).append(")\n\n")
            if (release.body.isNotBlank()) {
                append(release.body.take(350))
            }
        }

        AlertDialog.Builder(activityContext)
            .setTitle("Обновление TorPlay")
            .setMessage(msg)
            .setPositiveButton("Скачать и установить") { _, _ ->
                downloadAndInstallApk(activityContext, release)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadAndInstallApk(activityContext: Context, release: ReleaseInfo) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activityContext).apply {
            setTitle("Скачивание обновления")
            setMessage(release.apkName)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        Thread {
            runCatching {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val outFile = File(updatesDir, "TorPlay-latest.apk")

                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "TorPlay-TV")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("Пустой ответ")
                    val totalBytes = body.contentLength()
                    var downloaded = 0L
                    val buf = ByteArray(8192)

                    body.byteStream().use { input ->
                        FileOutputStream(outFile).use { output ->
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                downloaded += read
                                if (totalBytes > 0L) {
                                    val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    @Suppress("DEPRECATION")
                                    mainHandlerPost { progressDialog.progress = pct }
                                }
                            }
                            output.flush()
                        }
                    }
                }

                @Suppress("DEPRECATION")
                mainHandlerPost {
                    progressDialog.dismiss()
                    launchPackageInstaller(activityContext, outFile)
                }
            }.onFailure { err ->
                @Suppress("DEPRECATION")
                mainHandlerPost {
                    progressDialog.dismiss()
                    AlertDialog.Builder(activityContext)
                        .setTitle("Ошибка загрузки")
                        .setMessage(err.message ?: "Не удалось скачать APK")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun mainHandlerPost(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun launchPackageInstaller(activityContext: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activityContext.startActivity(installIntent)
    }
}
