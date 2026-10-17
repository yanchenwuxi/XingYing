package com.xing.tv.proxy

import android.util.Base64
import android.util.Log
import com.xing.tv.net.Http
import java.net.URL
import java.net.URLDecoder

/**
 * 播放地址嗅探：把需要解析的网页/接口地址还原成可直接播放的视频直链。
 *
 * 处理三类常见形态：
 *  1. 页面 HTML / JS 中内嵌的 m3u8 / mp4 地址
 *  2. 页面中 base64 编码后的视频地址
 *  3. 接口返回的 JSON 中携带的 url / video / src 字段
 *
 * 按 m3u8 > mp4 > flv 的优先级选取，同优先级取首个。
 */
object Sniffer {

    private const val TAG = "Sniffer"

    private val DIRECT_SUFFIX = setOf(
        "m3u8", "mp4", "flv", "mkv", "ts", "avi", "mov", "webm"
    )

    /** URL 形态的候选（含协议相对与转义形式） */
    private val URL_PATTERNS = listOf(
        Regex("""(?i)((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4|flv|mkv|ts|avi|mov|webm)(?:[^\s"'<>\\]*))"""),
        Regex("""(?i)((?:https?:)?//[^\s"'<>\\]*?/m3u8[^\s"'<>\\]*)"""),
        Regex("""(?i)["']((?:\.{0,2}/)?[^\s"'<>\\]+?\.(?:m3u8|mp4|flv))["']""")
    )

    /** JSON 字段形式 */
    private val JSON_FIELD = Regex(
        """(?i)["'](?:url|video|src|file|playurl|play_url)["']\s*:\s*["']([^"']+)["']"""
    )

    private val BASE64_BLOB = Regex("""[A-Za-z0-9+/]{80,}={0,2}""")

    fun isDirect(url: String): Boolean {
        val lower = url.substringBefore("?").substringBefore("#").lowercase()
        val ext = lower.substringAfterLast('.', "")
        return ext in DIRECT_SUFFIX
    }

    /** 尝试把任意地址解析成可播放直链；失败返回空串 */
    fun resolve(raw: String): String {
        if (raw.isBlank()) return ""
        if (isDirect(raw)) return raw
        return runCatching { sniff(raw) }.getOrElse {
            Log.w(TAG, "sniff $raw failed: $it")
            ""
        }
    }

    private fun sniff(raw: String): String {
        val body = Http.get(raw)
        if (body.isBlank()) return ""

        // 1) JSON 字段优先（接口型地址最可靠）
        JSON_FIELD.findAll(body).forEach { m ->
            val abs = absolutize(unescape(m.groupValues[1]), raw)
            if (isDirect(abs)) return abs
        }

        // 2) 正文中的直链
        val found = mutableListOf<String>()
        collect(body, raw, found)
        pickBest(found)?.let { return it }

        // 3) base64 片段解码后再找
        BASE64_BLOB.findAll(body).forEach { m ->
            val decoded = runCatching {
                String(Base64.decode(m.value, Base64.DEFAULT))
            }.getOrNull() ?: return@forEach
            collect(decoded, raw, found)
        }
        return pickBest(found) ?: ""
    }

    private fun collect(text: String, base: String, out: MutableList<String>) {
        URL_PATTERNS.forEach { p ->
            p.findAll(text).forEach { m ->
                val abs = absolutize(unescape(m.groupValues[1]), base)
                if (isDirect(abs)) out.add(abs)
            }
        }
    }

    private fun pickBest(candidates: List<String>): String? {
        for (ext in listOf("m3u8", "mp4", "flv")) {
            candidates.firstOrNull { it.contains(ext, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    private fun unescape(s: String): String {
        var t = s.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=")
        if (t.contains("\\u")) {
            t = runCatching { URLDecoder.decode(t, "UTF-8") }.getOrDefault(t)
        }
        return t
    }

    private fun absolutize(url: String, base: String): String {
        val u = url.trim().trim('"', '\'')
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "http:$u"
        return runCatching { URL(URL(base), u).toString() }.getOrDefault(u)
    }
}
