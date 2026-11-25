package com.torplay.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

class TorrServeStarter(
    private val storage: StorageManager,
    private val torrServeClient: TorrServeClient
) {

    fun isLocalUrl(url: String = storage.torrServeUrl): Boolean {
        val host = runCatching { Uri.parse(url.trim()).host.orEmpty().lowercase(Locale.US) }
            .getOrDefault("")
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
    }

    suspend fun ensureRunningSmart(
        context: Context,
        allowUiFallback: Boolean
    ): String? {
        torrServeClient.checkEcho()?.let { return it }

        if (!isLocalUrl(storage.torrServeUrl)) {
            return null
        }

        val triggered = withContext(Dispatchers.Main) {
            tryStartBackgroundServices(context.applicationContext)
        }

        if (triggered) {
            val attempts = if (allowUiFallback) 8 else 10
            repeat(attempts) {
                delay(450L)
                torrServeClient.checkEcho()?.let { version ->
                    AppLogger.i("TorrServe", "Тихий фоновый старт успешен: $version")
                    return version
                }
            }
        }

        if (!allowUiFallback) {
            return null
        }

        return torrServeClient.ensureRunning(context)
    }

    private fun tryStartBackgroundServices(appContext: Context): Boolean {
        val packages = discoverTorrServePackages(appContext)
        var anyStarted = false

        for (pkg in packages) {
            // 1. Explicit start-action service intents used by TorrServe MatriX
            for (action in KNOWN_START_ACTIONS) {
                val actionIntent = Intent(action).setPackage(pkg)
                if (startServiceSafely(appContext, actionIntent)) {
                    anyStarted = true
                }
            }

            // 2. Known explicit service components across TorrServe / MatriX builds
            for (cls in KNOWN_SERVICE_CLASSES) {
                val compIntent = Intent().setComponent(ComponentName(pkg, cls))
                if (startServiceSafely(appContext, compIntent)) {
                    anyStarted = true
                }
            }

            // 3. Dynamically discovered exported services inside installed TorrServe package
            for (cls in discoverExportedServices(appContext, pkg)) {
                val compIntent = Intent().setComponent(ComponentName(pkg, cls))
                if (startServiceSafely(appContext, compIntent)) {
                    anyStarted = true
                }
            }

            // 4. Broadcast wake-up for builds that listen to explicit start broadcast
            for (action in KNOWN_START_ACTIONS) {
                runCatching {
                    appContext.sendBroadcast(Intent(action).setPackage(pkg))
                    anyStarted = true
                }
            }
        }

        return anyStarted
    }

    private fun startServiceSafely(context: Context, intent: Intent): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            true
        }.getOrElse {
            runCatching {
                context.startService(intent) != null
            }.getOrDefault(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun discoverTorrServePackages(context: Context): List<String> {
        val result = LinkedHashSet<String>()
        val pm = context.packageManager

        // Add known package names first (works even under Android 11+ package visibility filtering)
        for (pkg in KNOWN_PACKAGES) {
            result.add(pkg)
        }

        // Also scan installed packages if visible
        runCatching {
            val installed = pm.getInstalledPackages(0)
            for (info in installed) {
                val name = info.packageName.orEmpty()
                if (name.contains("torrserve", ignoreCase = true) && !name.equals(context.packageName, ignoreCase = true)) {
                    result.add(name)
                }
            }
        }

        return result.toList()
    }

    @Suppress("DEPRECATION")
    private fun discoverExportedServices(context: Context, pkg: String): List<String> {
        val out = LinkedHashSet<String>()
        val pm = context.packageManager
        runCatching {
            val pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES)
            val services = pkgInfo.services ?: return@runCatching
            for (svc in services) {
                if (!svc.exported) continue
                val name = svc.name.orEmpty()
                if (name.contains("Torr", ignoreCase = true) ||
                    name.contains("Server", ignoreCase = true) ||
                    name.contains("Service", ignoreCase = true)
                ) {
                    out.add(name)
                }
            }
        }
        return out.toList()
    }

    companion object {
        private val KNOWN_PACKAGES = listOf(
            "ru.yourok.torrserve",
            "ru.yourok.torrserve.df",
            "ru.yourok.torrserve.matrix",
            "ru.yourok.torrserve.tv"
        )

        private val KNOWN_START_ACTIONS = listOf(
            "ru.yourok.torrserve.server.action.START",
            "ru.yourok.torrserve.ACTION_START"
        )

        private val KNOWN_SERVICE_CLASSES = listOf(
            "ru.yourok.torrserve.server.local.services.GlobalTorrServeService",
            "ru.yourok.torrserve.server.local.TorrService",
            "ru.yourok.torrserve.server.local.ServerService",
            "ru.yourok.torrserve.services.ServerService"
        )
    }
}
