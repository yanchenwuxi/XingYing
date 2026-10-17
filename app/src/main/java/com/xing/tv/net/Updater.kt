package com.xing.tv.net

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.xing.tv.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新（对齐星海 Updater）。
 * 检查顺序：1) 配置的 update.json  2) 可选 GitHub Releases API
 */
object Updater {

    /** 可在 Prefs / 常量中改成你的发布地址 */
    const val DEFAULT_UPDATE_JSON =
        "https://raw.githubusercontent.com/yanchenwuxi/Xinghai/main/update.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String = "",
        val size: Long = 0L
    )

    interface Listener {
        fun onChecking()
        fun onLatest(current: String)
        fun onAvailable(info: Info)
        fun onProgress(percent: Int, read: Long, total: Long)
        fun onInstalling()
        fun onError(msg: String)
    }

    fun currentCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (_: Throwable) {
        BuildConfig.VERSION_CODE
    }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
    } catch (_: Throwable) {
        BuildConfig.VERSION_NAME
    }

    fun check(ctx: Context, updateJsonUrl: String, listener: Listener) {
        listener.onChecking()
        Thread {
            try {
                val url = updateJsonUrl.ifBlank { DEFAULT_UPDATE_JSON }
                val body = httpGet(url)
                if (body.isBlank()) {
                    post(listener) { onError("无法获取更新信息") }
                    return@Thread
                }
                val info = parseInfo(body) ?: run {
                    post(listener) { onError("更新信息格式错误") }
                    return@Thread
                }
                val cur = currentCode(ctx)
                if (info.versionCode > cur) {
                    post(listener) { onAvailable(info) }
                } else {
                    post(listener) { onLatest(currentName(ctx)) }
                }
            } catch (t: Throwable) {
                post(listener) { onError(t.message ?: "检查失败") }
            }
        }.start()
    }

    fun downloadAndInstall(act: Activity, info: Info, listener: Listener) {
        if (info.apkUrl.isBlank()) {
            listener.onError("下载地址为空")
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (!act.packageManager.canRequestPackageInstalls()) {
                try {
                    act.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${act.packageName}")
                        )
                    )
                    listener.onError("请允许「安装未知应用」后重试")
                } catch (_: Throwable) {
                    listener.onError("无法打开安装权限设置")
                }
                return
            }
        }
        Thread {
            try {
                val dir = File(act.cacheDir, "update").apply { mkdirs() }
                val apk = File(dir, "xingying-update.apk")
                if (apk.exists()) apk.delete()
                download(info.apkUrl, apk, info.size, listener)
                act.runOnUiThread {
                    listener.onInstalling()
                    try {
                        installIntent(act, apk)
                    } catch (t: Throwable) {
                        listener.onError("安装失败：${t.message}")
                    }
                }
            } catch (t: Throwable) {
                act.runOnUiThread { listener.onError("下载失败：${t.message}") }
            }
        }.start()
    }

    private fun parseInfo(body: String): Info? = try {
        val o = JSONObject(body)
        Info(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            apkUrl = o.optString("apkUrl", o.optString("url", "")),
            notes = o.optString("notes", o.optString("body", "")),
            size = o.optLong("size", 0L)
        )
    } catch (_: Throwable) {
        null
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "XingYing/${BuildConfig.VERSION_NAME}")
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, target: File, expected: Long, listener: Listener) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "XingYing/${BuildConfig.VERSION_NAME}")
        }
        try {
            val total = if (expected > 0) expected else conn.contentLengthLong.coerceAtLeast(0L)
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
                        post(listener) { onProgress(pct, read, total) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun installIntent(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    private fun post(l: Listener, block: Listener.() -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { l.block() }
    }
}
