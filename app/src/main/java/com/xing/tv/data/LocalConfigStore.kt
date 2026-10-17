package com.xing.tv.data

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser
import java.io.File

/**
 * 本地接口文件导入。
 *
 * 很多用户手上是别人给的 `.json` 配置文件，而不是一个可访问的 URL。
 * 这里走系统文件选择器（SAF）读取 `content://`，**不需要任何存储权限**，
 * 读到的内容落到应用私有目录后再用 `local://` 协议交给 [com.xing.tv.api.ConfigLoader]。
 *
 * 落盘而不是只留一个 URI，是因为 SAF 授予的读取权限会在应用重启后失效，
 * 直接存 `content://` 会导致重启即失效。复制进私有目录后配置就是自包含的。
 */
object LocalConfigStore {

    /** 本地配置的协议前缀，与 http:// / asset:// 平级 */
    const val PREFIX = "local://"

    private const val DIR = "local_configs"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isLocal(url: String): Boolean = url.startsWith(PREFIX)

    private fun dir(): File = File(appContext.filesDir, DIR).apply { mkdirs() }

    /**
     * 从 SAF 选中的文件导入。
     * @param displayName 文件选择器给的原始文件名，用于生成可读的条目名
     * @return 可直接使用的 `local://` 地址
     */
    @Throws(Exception::class)
    fun importFrom(uri: Uri, displayName: String?): String {
        val text = appContext.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("无法读取所选文件")
        return save(text, displayName)
    }

    /** 直接写入文本（直播源包装 JSON 等），返回 `local://` 地址 */
    @Throws(Exception::class)
    fun importText(text: String, displayName: String?): String = save(text, displayName)

    /** 校验并落盘，返回 `local://` 地址 */
    @Throws(Exception::class)
    fun save(text: String, displayName: String?): String {
        // 统一去掉可能的 UTF-8 BOM，落盘的副本保持干净
        val clean = text.removePrefix("\uFEFF").trim()
        val toStore = when {
            looksLikeLivePlaylist(clean) -> wrapLivePlaylist(clean, displayName)
            else -> {
                validateConfigText(clean)
                clean
            }
        }
        val file = File(dir(), fileName(displayName, toStore))
        file.writeText(toStore)
        return PREFIX + file.name
    }

    private fun looksLikeLivePlaylist(body: String): Boolean {
        val head = body.lineSequence().take(12).joinToString("\n")
        if (head.contains("#EXTM3U", ignoreCase = true)) return true
        if (head.contains("#EXTINF", ignoreCase = true)) return true
        if (body.contains("#genre#", ignoreCase = true)) return true
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        return lines.size >= 3 && lines.take(5).all { it.startsWith("http") || "," in it }
    }

    /** 本地 m3u / 分组文本包装为 TVBox lives 配置 */
    private fun wrapLivePlaylist(body: String, displayName: String?): String {
        val rawName = "live_raw_" + Integer.toHexString(body.hashCode()) + ".m3u"
        File(dir(), rawName).writeText(body)
        val name = (displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }) ?: "本地直播"
        val safe = name.replace("\"", "'")
        return """{"sites":[],"lives":[{"name":"$safe","url":"local-m3u://$rawName","type":0}]}"""
    }

    /**
     * 校验内容确实是 TVBox 配置，防止把图片、二进制或任意 JSON 误当配置塞进来。
     *
     * 单独抽出来是为了能在 JVM 单测里覆盖；[save] 只负责落盘。
     * @throws IllegalStateException 内容不合法，message 直接给用户看
     */
    @Throws(IllegalStateException::class)
    internal fun validateConfigText(text: String) {
        if (text.isBlank()) throw IllegalStateException("文件内容为空")

        val body = runCatching { com.xing.tv.api.ConfigLoader.cleanForParse(text) }
            .getOrElse { throw IllegalStateException("不是有效的 TVBox 配置：${it.message}") }
        val root = runCatching { JsonParser.parseString(body) }
            .getOrElse { throw IllegalStateException("不是有效的 TVBox 配置：无法解析 JSON") }
        // 顶层数组视为站点数组；对象则至少含一组配置字段
        if (root.isJsonArray) return
        val o = runCatching { root.asJsonObject }
            .getOrElse { throw IllegalStateException("不是有效的 TVBox 配置：无法解析 JSON") }
        if (!o.has("sites") && !o.has("urls") && !o.has("lives") &&
            !o.has("stores") && !o.has("storehouse")
        ) {
            throw IllegalStateException("不是有效的 TVBox 配置：缺少 sites / urls / lives 字段")
        }
    }

    /**
     * 文件名 = 可读名 + 内容哈希。
     *
     * 只用文件名会重名覆盖（不同目录下同名 tvbox.json 很常见），
     * 只用哈希又让人在列表里认不出是哪份配置，两者结合。
     */
    internal fun fileName(displayName: String?, text: String): String {
        val base = displayName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]"), "")
            ?.take(24)
            .orEmpty()
            .ifEmpty { "config" }
        return "$base-${Integer.toHexString(text.hashCode())}.json"
    }

    /** 读取本地配置原文；文件不存在或被删时返回 null */
    fun read(url: String): String? {
        if (!isLocal(url)) return null
        return runCatching {
            val name = url.removePrefix(PREFIX)
            // 防目录穿越：文件名里不该出现路径分隔符
            if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
            val f = File(dir(), name)
            if (f.exists()) f.readText() else null
        }.getOrNull()
    }

    /** 删除本地配置文件（删除接口源条目时一并调用） */
    fun delete(url: String) {
        if (!isLocal(url)) return
        runCatching {
            val name = url.removePrefix(PREFIX)
            if (name.contains('/') || name.contains('\\') || name.contains("..")) return
            File(dir(), name).delete()
        }
    }

    /** 本地配置占用字节数，供设置页缓存统计 */
    fun bytes(): Long = runCatching {
        dir().listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)
}
