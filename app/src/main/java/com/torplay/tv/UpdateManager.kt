package com.torplay.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.graphics.Typeface
import android.view.View
import android.widget.ScrollView
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UpdateManager(private val context: Context) {

    data class ReleaseInfo(
        val tagName: String,
        val title: String,
        val apkUrl: String,
        val apkName: String,
        val apkSizeBytes: Long,
        val body: String,
        val versionLabel: String
    )

    data class CheckResult(
        val release: ReleaseInfo?,
        val error: String? = null
    )

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isDialogShowing: Boolean = false

    suspend fun checkLatestRelease(): ReleaseInfo? = checkLatestReleaseDetailed().release

    suspend fun checkLatestReleaseDetailed(): CheckResult = withContext(Dispatchers.IO) {
        var lastErr: String? = null

        // 1. Primary: GitHub API /releases/latest
        val latestAttempt = fetchReleaseObject(LATEST_RELEASE_URL)
        latestAttempt.first?.let { obj ->
            parseReleaseJson(obj)?.let { rel ->
                AppLogger.i("Update", "GitHub /latest: ${rel.versionLabel} (${rel.apkName}, tag=${rel.tagName})")
                return@withContext CheckResult(rel, null)
            }
        }
        if (latestAttempt.second != null) {
            lastErr = latestAttempt.second
        }

        // 2. Fallback: GitHub API /releases?per_page=5
        val listReq = Request.Builder()
            .url(ALL_RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TorPlay-TV")
            .build()

        val listResult = runCatching {
            apiClient.newCall(listReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastErr = "GitHub API HTTP ${resp.code}"
                    return@use null
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@use null
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    val parsed = parseReleaseJson(obj)
                    if (parsed != null) return@use parsed
                }
                null
            }
        }.onFailure { err ->
            lastErr = err.message?.take(60) ?: err.javaClass.simpleName
        }.getOrNull()

        if (listResult != null) {
            AppLogger.i("Update", "GitHub /releases: ${listResult.versionLabel} (${listResult.apkName})")
            return@withContext CheckResult(listResult, null)
        }

        // 3. Fallback without GitHub API rate limits: follow /releases/latest web redirect & expanded_assets
        val webFallback = fetchReleaseViaWebFallback()
        if (webFallback != null) {
            AppLogger.i("Update", "GitHub web fallback: ${webFallback.versionLabel} (${webFallback.apkName})")
            return@withContext CheckResult(webFallback, null)
        }

        val errMsg = lastErr ?: "Нет ответа от GitHub"
        AppLogger.w("Update", "Проверка обновлений не удалась: $errMsg")
        CheckResult(null, errMsg)
    }

    private fun fetchReleaseObject(url: String): Pair<JSONObject?, String?> {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TorPlay-TV")
            .build()

        return runCatching {
            apiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null to "GitHub API HTTP ${resp.code}"
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@use null to "Пустой ответ GitHub"
                JSONObject(raw) to null
            }
        }.getOrElse { err ->
            null to (err.message?.take(60) ?: err.javaClass.simpleName)
        }
    }

    private fun fetchReleaseViaWebFallback(): ReleaseInfo? {
        return runCatching {
            val latestReq = Request.Builder()
                .url(WEB_LATEST_RELEASE_URL)
                .header("User-Agent", "TorPlay-TV")
                .build()
            val tag = apiClient.newCall(latestReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val finalUrl = resp.request.url.toString()
                if (finalUrl.contains("/releases/tag/")) {
                    finalUrl.substringAfter("/releases/tag/").substringBefore('?').trim()
                } else {
                    ""
                }
            }
            if (tag.isBlank()) return@runCatching null

            val assetsReq = Request.Builder()
                .url("$WEB_EXPANDED_ASSETS_PREFIX$tag")
                .header("User-Agent", "TorPlay-TV")
                .build()
            val html = apiClient.newCall(assetsReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                resp.body?.string().orEmpty()
            }
            val match = WEB_APK_HREF_REGEX.find(html) ?: return@runCatching null
            val relHref = match.groupValues[1].trim()
            val apkUrl = if (relHref.startsWith("http")) relHref else "https://github.com$relHref"
            val apkName = relHref.substringAfterLast('/').ifBlank { "TorPlay-update.apk" }
            val verLabel = extractVersionLabel(apkName, tag, tag)
            ReleaseInfo(
                tagName = tag,
                title = "TorPlay $verLabel",
                apkUrl = apkUrl,
                apkName = apkName,
                apkSizeBytes = 0L,
                body = "Релиз $tag на GitHub (получено через резервный канал).",
                versionLabel = verLabel
            )
        }.getOrNull()
    }

    private fun parseReleaseJson(obj: JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name").trim()
        val name = obj.optString("name").trim().ifBlank { tag }
        val body = obj.optString("body").trim()

        var apkUrl = ""
        var apkName = "TorPlay-update.apk"
        var apkSize = 0L
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name")
                val dlUrl = asset.optString("browser_download_url")
                if (assetName.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                    apkUrl = dlUrl
                    apkName = assetName
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        if (apkUrl.isBlank() || tag.isBlank()) return null

        val extractedVersion = extractVersionLabel(apkName, tag, name)
        return ReleaseInfo(
            tagName = tag,
            title = name,
            apkUrl = apkUrl,
            apkName = apkName,
            apkSizeBytes = apkSize,
            body = body,
            versionLabel = extractedVersion
        )
    }

    fun isNewerRelease(release: ReleaseInfo, currentVersionName: String): Boolean {
        return compareVersions(release.versionLabel, currentVersionName) > 0
    }

    fun showUpdateDialog(
        activityContext: Context,
        release: ReleaseInfo,
        currentVersionName: String,
        manualCheck: Boolean
    ) {
        val isNewer = isNewerRelease(release, currentVersionName)
        AppLogger.i("Update", "Сравнение версий: текущая=$currentVersionName, GitHub=${release.versionLabel}, новее=$isNewer, manual=$manualCheck")
        if (!manualCheck && !isNewer) {
            return
        }
        if (isDialogShowing) {
            return
        }
        if (activityContext is Activity && (activityContext.isFinishing || activityContext.isDestroyed)) {
            return
        }
        isDialogShowing = true

        val dialog = Dialog(activityContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_update)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activityContext.resources.displayMetrics.widthPixels * 0.68).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val tvBadge = dialog.findViewById<TextView>(R.id.tvUpdateBadge)
        val tvVersions = dialog.findViewById<TextView>(R.id.tvUpdateVersions)
        val tvSize = dialog.findViewById<TextView>(R.id.tvUpdateSize)
        val tvNotes = dialog.findViewById<TextView>(R.id.tvUpdateNotes)
        val layoutProgress = dialog.findViewById<LinearLayout>(R.id.layoutUpdateProgress)
        val tvProgressText = dialog.findViewById<TextView>(R.id.tvUpdateProgressText)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressUpdateBar)
        val tvPermissionHint = dialog.findViewById<TextView>(R.id.tvUpdatePermissionHint)
        val btnChangelog = dialog.findViewById<TextView>(R.id.btnUpdateChangelog)
        val btnSecondary = dialog.findViewById<TextView>(R.id.btnUpdateSecondary)
        val btnPrimary = dialog.findViewById<TextView>(R.id.btnUpdatePrimary)

        if (isNewer) {
            tvBadge.text = activityContext.getString(R.string.update_badge_new)
            tvBadge.setBackgroundResource(R.drawable.bg_badge_1080)
            btnPrimary.text = activityContext.getString(R.string.update_btn_download)
            btnSecondary.text = activityContext.getString(R.string.update_btn_later)
        } else {
            tvBadge.text = activityContext.getString(R.string.update_badge_latest)
            tvBadge.setBackgroundResource(R.drawable.bg_badge_default)
            btnPrimary.text = activityContext.getString(R.string.update_btn_reinstall)
            btnSecondary.text = activityContext.getString(R.string.update_btn_close)
        }

        tvVersions.text = "Установлена: $currentVersionName  →  GitHub: ${release.versionLabel}"
        val sizePart = if (release.apkSizeBytes > 0L) " • ${formatBytes(release.apkSizeBytes)}" else ""
        tvSize.text = "${release.apkName}$sizePart (${release.tagName})"
        tvNotes.text = resolvePatchNotes(release.versionLabel, release.body)

        var activeCall: Call? = null
        val cancelled = AtomicBoolean(false)
        var isDownloading = false
        var downloadedApkFile: File? = null

        fun tryInstallOrRequestPermission(apkFile: File) {
            if (!canInstallUnknownApps(activityContext)) {
                tvPermissionHint.text = activityContext.getString(R.string.update_permission_hint)
                tvPermissionHint.visibility = View.VISIBLE
                tvProgressText.text = activityContext.getString(R.string.update_ready)
                tvProgressText.setTextColor(ContextCompat.getColor(activityContext, R.color.status_warn))
                btnPrimary.text = activityContext.getString(R.string.update_btn_grant_permission)
                btnPrimary.isEnabled = true
                btnPrimary.alpha = 1.0f
                btnSecondary.text = activityContext.getString(R.string.update_btn_close)
                btnPrimary.requestFocus()
                return
            }
            tvPermissionHint.visibility = View.GONE
            launchPackageInstaller(activityContext, apkFile)
        }

        btnChangelog.setOnClickListener {
            showChangelogDialog(activityContext)
        }

        btnSecondary.setOnClickListener {
            if (isDownloading) {
                cancelled.set(true)
                activeCall?.cancel()
            } else {
                dialog.dismiss()
            }
        }

        btnPrimary.setOnClickListener {
            if (isDownloading) return@setOnClickListener

            val readyFile = downloadedApkFile
            if (readyFile != null && readyFile.exists() && readyFile.length() > 0L) {
                if (!canInstallUnknownApps(activityContext)) {
                    openUnknownSourcesSettings(activityContext)
                    btnPrimary.text = activityContext.getString(R.string.update_btn_install)
                } else {
                    tryInstallOrRequestPermission(readyFile)
                }
                return@setOnClickListener
            }

            val freedBytes = reclaimCacheBeforeUpdate()
            val freeBytes = getAvailableInternalBytes()
            val apkExpected = release.apkSizeBytes.takeIf { it > 0L } ?: (55L * 1024L * 1024L)
            val minRequiredBytes = apkExpected * 2L + 90L * 1024L * 1024L
            val recommendedBytes = apkExpected * 3L + 140L * 1024L * 1024L
            AppLogger.i(
                "Update",
                "Проверка памяти: свободно ${formatBytes(freeBytes)}, APK ${formatBytes(apkExpected)}, очищено кэша ${formatBytes(freedBytes)}"
            )
            if (freeBytes in 1L until minRequiredBytes) {
                val needToFree = (minRequiredBytes - freeBytes).coerceAtLeast(32L * 1024L * 1024L)
                layoutProgress.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                tvProgressText.setTextColor(ContextCompat.getColor(activityContext, R.color.status_err))
                tvProgressText.text = "Недостаточно памяти для установки (свободно ${formatBytes(freeBytes)})"
                tvPermissionHint.text = "Освободите минимум ${formatBytes(needToFree)} в памяти устройства (Настройки → Хранилище) и нажмите ещё раз."
                tvPermissionHint.visibility = View.VISIBLE
                return@setOnClickListener
            }

            isDownloading = true
            cancelled.set(false)
            dialog.setCancelable(false)
            if (freeBytes in 1L until recommendedBytes) {
                val extraNeed = (recommendedBytes - freeBytes).coerceAtLeast(32L * 1024L * 1024L)
                tvPermissionHint.text = "Мало памяти (свободно ${formatBytes(freeBytes)}). Если установка не пройдёт, освободите ещё ~${formatBytes(extraNeed)}."
                tvPermissionHint.visibility = View.VISIBLE
            } else {
                tvPermissionHint.visibility = View.GONE
            }
            layoutProgress.visibility = View.VISIBLE
            progressBar.isIndeterminate = release.apkSizeBytes <= 0L
            progressBar.progress = 0
            tvProgressText.setTextColor(ContextCompat.getColor(activityContext, R.color.accent_emerald))
            tvProgressText.text = activityContext.getString(R.string.update_downloading)
            btnPrimary.isEnabled = false
            btnPrimary.alpha = 0.5f
            btnSecondary.text = activityContext.getString(R.string.update_btn_cancel_download)
            btnSecondary.requestFocus()

            Thread {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                cleanOldApks(updatesDir)
                val outFile = File(updatesDir, "TorPlay-latest.apk")

                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "TorPlay-TV")
                    .build()
                val call = downloadClient.newCall(req)
                activeCall = call

                val result = runCatching {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("Пустой ответ сервера")
                        val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                        var downloaded = 0L
                        var lastUiUpdate = 0L
                        val buf = ByteArray(16_384)

                        body.byteStream().use { input ->
                            FileOutputStream(outFile).use { output ->
                                while (true) {
                                    if (cancelled.get()) {
                                        throw InterruptedException("CANCELLED")
                                    }
                                    val read = input.read(buf)
                                    if (read == -1) break
                                    output.write(buf, 0, read)
                                    downloaded += read

                                    val now = System.currentTimeMillis()
                                    if (now - lastUiUpdate >= 120L || (totalBytes > 0L && downloaded >= totalBytes)) {
                                        lastUiUpdate = now
                                        val snapDown = downloaded
                                        mainHandler.post {
                                            if (!dialog.isShowing) return@post
                                            if (totalBytes > 0L) {
                                                val pct = ((snapDown * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                                progressBar.isIndeterminate = false
                                                progressBar.progress = pct
                                                tvProgressText.text = "Загрузка: $pct% (${formatBytes(snapDown)} / ${formatBytes(totalBytes)})"
                                            } else {
                                                progressBar.isIndeterminate = true
                                                tvProgressText.text = "Загрузка: ${formatBytes(snapDown)}"
                                            }
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                    outFile
                }

                mainHandler.post {
                    isDownloading = false
                    activeCall = null
                    dialog.setCancelable(true)
                    btnPrimary.isEnabled = true
                    btnPrimary.alpha = 1.0f

                    if (cancelled.get()) {
                        outFile.delete()
                        if (dialog.isShowing) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = 0
                            tvProgressText.setTextColor(ContextCompat.getColor(activityContext, R.color.status_warn))
                            tvProgressText.text = activityContext.getString(R.string.update_cancelled)
                            btnSecondary.text = activityContext.getString(R.string.update_btn_close)
                            btnPrimary.text = activityContext.getString(
                                if (isNewer) R.string.update_btn_download else R.string.update_btn_reinstall
                            )
                            btnPrimary.requestFocus()
                        }
                        return@post
                    }

                    result.onSuccess { readyApk ->
                        downloadedApkFile = readyApk
                        if (dialog.isShowing) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = 100
                            tvProgressText.setTextColor(ContextCompat.getColor(activityContext, R.color.status_ok))
                            tvProgressText.text = activityContext.getString(R.string.update_ready)
                            btnSecondary.text = activityContext.getString(R.string.update_btn_close)
                            btnPrimary.text = activityContext.getString(R.string.update_btn_install)
                            tryInstallOrRequestPermission(readyApk)
                        }
                    }.onFailure { err ->
                        outFile.delete()
                        if (dialog.isShowing) {
                            progressBar.isIndeterminate = false
                            tvProgressText.setTextColor(ContextCompat.getColor(activityContext, R.color.status_err))
                            tvProgressText.text = "Ошибка загрузки: ${err.message ?: "нет ответа"}"
                            btnSecondary.text = activityContext.getString(R.string.update_btn_close)
                            btnPrimary.text = activityContext.getString(R.string.update_btn_download)
                            btnPrimary.requestFocus()
                        }
                    }
                }
            }.start()
        }

        dialog.setOnDismissListener {
            isDialogShowing = false
            if (isDownloading) {
                cancelled.set(true)
                activeCall?.cancel()
            }
        }

        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        if (isNewer) {
            btnPrimary.requestFocus()
        } else {
            btnSecondary.requestFocus()
        }
    }

    private fun cleanOldApks(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) {
                runCatching { f.delete() }
            }
        }
    }

    private fun reclaimCacheBeforeUpdate(): Long {
        var freed = 0L
        val dirs = listOfNotNull(
            File(context.cacheDir, "updates"),
            File(context.cacheDir, "posters"),
            context.externalCacheDir?.let { File(it, "updates") }
        )
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val sz = f.length().coerceAtLeast(0L)
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        freed += sz
                    }
                }
            }
        }
        return freed
    }

    private fun getAvailableInternalBytes(): Long {
        return runCatching {
            val stat = StatFs(context.cacheDir.absolutePath)
            stat.availableBytes
        }.getOrDefault(0L)
    }

    private fun canInstallUnknownApps(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun openUnknownSourcesSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                ctx.startActivity(intent)
            }.onFailure {
                Toast.makeText(
                    ctx,
                    "Откройте Настройки → Приложения → Специальный доступ → Установка неизвестных приложений",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchPackageInstaller(activityContext: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            activityContext.startActivity(installIntent)
        }.onFailure { err ->
            Toast.makeText(
                activityContext,
                "Не удалось открыть установщик: ${err.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun showChangelogDialog(activityContext: Context) {
        if (activityContext is Activity && (activityContext.isFinishing || activityContext.isDestroyed)) {
            return
        }
        val dialog = Dialog(activityContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_changelog)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activityContext.resources.displayMetrics.widthPixels * 0.78).toInt(),
            (activityContext.resources.displayMetrics.heightPixels * 0.84).toInt()
        )

        val progress = dialog.findViewById<ProgressBar>(R.id.progressChangelog)
        val btnClose = dialog.findViewById<TextView>(R.id.btnChangelogClose)
        val container = dialog.findViewById<LinearLayout>(R.id.layoutChangelogEntries)

        fun renderEntries(entries: List<ChangelogEntry>) {
            container.removeAllViews()
            val density = activityContext.resources.displayMetrics.density
            val dp6 = (6 * density).toInt()
            val dp12 = (12 * density).toInt()
            val dp14 = (14 * density).toInt()

            entries.forEachIndexed { idx, entry ->
                val titleView = TextView(activityContext).apply {
                    text = if (entry.dateLabel.isNotBlank()) {
                        "v${entry.version}  •  ${entry.dateLabel}"
                    } else {
                        "v${entry.version}"
                    }
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(
                        ContextCompat.getColor(
                            activityContext,
                            if (idx == 0) R.color.accent_emerald else R.color.text_primary
                        )
                    )
                    setPadding(0, if (idx == 0) 0 else dp12, 0, dp6)
                }
                val bodyView = TextView(activityContext).apply {
                    text = entry.notes
                    textSize = 13f
                    setLineSpacing(4f, 1.0f)
                    setTextColor(ContextCompat.getColor(activityContext, R.color.text_secondary))
                    setPadding(0, 0, 0, dp14)
                }
                container.addView(titleView)
                container.addView(bodyView)

                if (idx < entries.lastIndex) {
                    val divider = View(activityContext).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * density).toInt().coerceAtLeast(1)
                        )
                        setBackgroundColor(ContextCompat.getColor(activityContext, R.color.border_subtle))
                    }
                    container.addView(divider)
                }
            }
        }

        renderEntries(BUILT_IN_CHANGELOG)
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        btnClose.requestFocus()

        // Background refresh from GitHub to include any newer releases published after this build
        progress.visibility = View.VISIBLE
        Thread {
            val merged = buildMergedChangelogFromGitHub()
            mainHandler.post {
                if (!dialog.isShowing) return@post
                progress.visibility = View.GONE
                if (merged.isNotEmpty()) {
                    renderEntries(merged)
                }
            }
        }.start()
    }

    private fun buildMergedChangelogFromGitHub(): List<ChangelogEntry> {
        val byVersion = LinkedHashMap<String, ChangelogEntry>()
        for (local in BUILT_IN_CHANGELOG) {
            byVersion[local.version] = local
        }

        runCatching {
            val req = Request.Builder()
                .url(CHANGELOG_RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TorPlay-TV")
                .build()
            apiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val raw = resp.body?.string().orEmpty().trim()
                if (!raw.startsWith("[")) return@use
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    val rel = parseReleaseJson(obj) ?: continue
                    val ver = rel.versionLabel.trim()
                    if (ver.isBlank()) continue
                    val published = obj.optString("published_at", "").take(10)
                    val existing = byVersion[ver]
                    val resolvedNotes = resolvePatchNotes(ver, rel.body)
                    if (existing == null) {
                        byVersion[ver] = ChangelogEntry(ver, published, resolvedNotes)
                    } else if (isMeaningfulReleaseBody(rel.body)) {
                        byVersion[ver] = existing.copy(
                            dateLabel = existing.dateLabel.ifBlank { published },
                            notes = rel.body.trim()
                        )
                    }
                }
            }
        }

        return byVersion.values.sortedWith { a, b -> compareVersions(b.version, a.version) }
    }

    private fun isMeaningfulReleaseBody(rawBody: String): Boolean {
        val clean = rawBody.trim()
        if (clean.length < 18) return false
        val lower = clean.lowercase(Locale.ROOT)
        if (lower.startsWith("релиз ") && lower.contains("на github")) return false
        if (lower.startsWith("auto-built") || lower.startsWith("automated build") || lower.startsWith("автосборка")) {
            return false
        }
        return true
    }

    private fun resolvePatchNotes(versionLabel: String, githubBody: String): String {
        if (isMeaningfulReleaseBody(githubBody)) {
            return githubBody.trim()
        }
        val cleanVer = versionLabel.trim().removePrefix("v").removePrefix("V")
        val exact = BUILT_IN_CHANGELOG.firstOrNull { it.version.equals(cleanVer, ignoreCase = true) }
        if (exact != null) {
            return "Что нового в v${exact.version}:\n${exact.notes}"
        }
        val latest = BUILT_IN_CHANGELOG.first()
        return if (compareVersions(cleanVer, latest.version) > 0) {
            "Доступна новая сборка v$cleanVer на GitHub.\n\nПоследний известный патч (v${latest.version}):\n${latest.notes}"
        } else {
            "Изменения последнего патча (v${latest.version}):\n${latest.notes}"
        }
    }

    data class ChangelogEntry(
        val version: String,
        val dateLabel: String,
        val notes: String
    )

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/pskovland007/TorPlay/releases/latest"
        private const val ALL_RELEASES_URL =
            "https://api.github.com/repos/pskovland007/TorPlay/releases?per_page=5"
        private const val CHANGELOG_RELEASES_URL =
            "https://api.github.com/repos/pskovland007/TorPlay/releases?per_page=15"

        private val BUILT_IN_CHANGELOG: List<ChangelogEntry> = listOf(
            ChangelogEntry(
                version = "1.0.17",
                dateLabel = "Последний патч",
                notes = "• Окно обновлений теперь показывает список изменений патча и кнопку «История изменений».\n" +
                    "• Добавлен фильтр «Дубляж» и улучшена верхняя панель фильтров (4K / 1080p / 720p / Дубляж) в поиске и в окне «Раздачи» плеера.\n" +
                    "• Умное соотношение сторон экрана: для нового фильма сбрасывается на стандарт, а при продолжении просмотра запоминается.\n" +
                    "• В карточке фильма теперь всегда подгружается длительность.\n" +
                    "• Добавлен список фильмов «Буду смотреть» — кнопка в карточке фильма и рядом с «Избранным» и «Историей»."
            ),
            ChangelogEntry(
                version = "1.0.16",
                dateLabel = "Витрина и постеры",
                notes = "• Исправлена загрузка обложек TMDB через автоматическую цепочку резервных зеркал.\n" +
                    "• На плитках фильмов добавлены год в скобках, главный жанр, цветной рейтинг, длительность и сиды/качество.\n" +
                    "• Новая полка «Случайный топ дня (7.5+ с сидами)» — 6–8 проверенных фильмов, обновляются каждый день."
            ),
            ChangelogEntry(
                version = "1.0.15",
                dateLabel = "Облегчённый APK",
                notes = "• Вес APK уменьшен примерно в 3 раза (ARM-only сборка со сжатием библиотек для Mi Box).\n" +
                    "• Добавлена автоочистка кэша и проверка свободной памяти перед обновлением."
            ),
            ChangelogEntry(
                version = "1.0.14",
                dateLabel = "Плеер и шторка Android",
                notes = "• Исправлено перекрытие интерфейса верхней шторкой Android (полноэкранный режим).\n" +
                    "• Оптимизированы флаги VLC (убрано рассыпание картинки, возвращён быстрый многопоточный декодер).\n" +
                    "• Добавлена подсказка при запуске 4K на устройствах без аппаратного 4K."
            ),
            ChangelogEntry(
                version = "1.0.13",
                dateLabel = "Иконки и избранное",
                notes = "• Новые полноразмерный ТВ-баннер Android TV без текста и адаптивные иконки для телефонов/планшетов.\n" +
                    "• Плашка скорости/пиров сделана более прозрачной.\n" +
                    "• Добавление в избранное прямо из истории просмотров."
            ),
            ChangelogEntry(
                version = "1.0.11",
                dateLabel = "Хаб настроек и плашка",
                notes = "• Новое меню настроек в виде 6 крупных плиток (2×3) со статусами.\n" +
                    "• Постоянная плашка скорости и пиров в углу плеера со сдвигом пикселей для плазмы/OLED.\n" +
                    "• Кнопка «Раздачи» прямо в плеере для быстрой смены потока с той же секунды."
            ),
            ChangelogEntry(
                version = "1.0.10",
                dateLabel = "Буст звука и мёртвые раздачи",
                notes = "• Умный буст звука: сброс на новом фильме, запоминание при продолжении из истории.\n" +
                    "• Окно альтернативных источников при запуске раздачи без сидов.\n" +
                    "• Кнопка быстрого переключения движка (VLC HW / VLC SW / Media3) в плеере."
            ),
            ChangelogEntry(
                version = "1.0.9",
                dateLabel = "Сеть и DoH DNS",
                notes = "• Встроенный DoH DNS (AdGuard / Google / Cloudflare / Quad9), обход DPI и поддержка прокси.\n" +
                    "• Инструмент «Проверить зеркала» и авто-резерв витрины."
            )
        )
        private const val WEB_LATEST_RELEASE_URL =
            "https://github.com/pskovland007/TorPlay/releases/latest"
        private const val WEB_EXPANDED_ASSETS_PREFIX =
            "https://github.com/pskovland007/TorPlay/releases/expanded_assets/"

        private val APK_VERSION_REGEX = Regex("v(\\d+(?:\\.\\d+)+(?:-[A-Za-z0-9.]+)?)", RegexOption.IGNORE_CASE)
        private val SEMVER_REGEX = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
        private val STAGE_REGEX = Regex("stage(\\d+)(?:\\.(\\d+))?([a-z])?", RegexOption.IGNORE_CASE)
        private val WEB_APK_HREF_REGEX = Regex("href=\"(/[^\"]+/releases/download/[^\"]+\\.apk)\"", RegexOption.IGNORE_CASE)

        fun extractVersionLabel(apkName: String, tagName: String, releaseTitle: String): String {
            val cleanApk = apkName.removeSuffix(".apk").removeSuffix(".APK")
            APK_VERSION_REGEX.find(cleanApk)?.groupValues?.get(1)?.let { return it }
            APK_VERSION_REGEX.find(tagName)?.groupValues?.get(1)?.let { return it }
            APK_VERSION_REGEX.find(releaseTitle)?.groupValues?.get(1)?.let { return it }
            return tagName.removePrefix("v").removePrefix("V").trim()
        }

        fun compareVersions(remote: String, current: String): Int {
            val rSem = parseSemver(remote)
            val cSem = parseSemver(current)
            if (rSem != null && cSem != null) {
                for (i in 0 until 3) {
                    if (rSem[i] != cSem[i]) return rSem[i].compareTo(cSem[i])
                }
            }

            val rStage = parseStage(remote)
            val cStage = parseStage(current)
            if (rStage != null && cStage != null) {
                for (i in 0 until 3) {
                    if (rStage[i] != cStage[i]) return rStage[i].compareTo(cStage[i])
                }
            } else if (rSem != null && cSem != null) {
                // Same semver; release without "-stage..." suffix is considered newer than stage pre-release
                if (rStage == null && cStage != null) return 1
                if (rStage != null && cStage == null) return -1
            }

            return 0
        }

        private fun parseSemver(raw: String): IntArray? {
            val cleaned = raw.lowercase(Locale.US).replace(STAGE_REGEX, "")
            val m = SEMVER_REGEX.find(cleaned) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: 0
            val minor = m.groupValues[2].toIntOrNull() ?: 0
            val patch = m.groupValues[3].toIntOrNull() ?: 0
            return intArrayOf(major, minor, patch)
        }

        private fun parseStage(raw: String): IntArray? {
            val m = STAGE_REGEX.find(raw.lowercase(Locale.US)) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: 0
            val minor = m.groupValues[2].toIntOrNull() ?: 0
            val letter = m.groupValues[3].firstOrNull()?.let { ch ->
                if (ch in 'a'..'z') (ch - 'a') + 1 else 0
            } ?: 0
            return intArrayOf(major, minor, letter)
        }
    }
}
