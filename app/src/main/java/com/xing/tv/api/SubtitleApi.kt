package com.xing.tv.api

import com.google.gson.JsonParser
import com.xing.tv.net.Http
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 字幕源：迅雷公开接口（免 Token）。
 * v1.3.0 从 assrt 切换到 https://api-shoulei-ssl.xunlei.com/oracle/subtitle
 */
object SubtitleApi {

    private const val API = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name="
    private const val TAIL = "&size=20&page=1"

    data class SubHit(
        val id: Long,
        val title: String,
        val lang: String = "",
        val format: String = "",
        val url: String = "",
        val score: Double = 0.0
    )

    data class SubFile(val name: String, val url: String, val size: String = "")

    private val urlById = ConcurrentHashMap<Long, String>()

    fun search(@Suppress("UNUSED_PARAMETER") token: String, keyword: String): List<SubHit> {
        val kw = cleanTitle(keyword)
        if (kw.isEmpty()) throw IllegalStateException("请输入片名")
        val body = runCatching {
            Http.get(API + URLEncoder.encode(kw, "UTF-8") + TAIL, api = true)
        }.getOrElse {
            throw IllegalStateException("字幕搜索失败：网络请求不成功")
        }
        val arr = pickArray(body) ?: throw IllegalStateException("字幕搜索失败：返回内容无法识别")
        val hits = mutableListOf<SubHit>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val url = o.str("url")
            val ext = o.str("ext").lowercase(Locale.ROOT)
            if (url.isEmpty() || !okExt(ext)) continue
            val title = o.str("simple_name").ifEmpty { o.str("name") }.ifEmpty { "字幕" }
            val dur = runCatching { o.get("duration").asLong }.getOrDefault(0L)
            val lang = langOf(o)
            val second = buildString {
                append(lang)
                if (dur > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(durText(dur))
                }
                if (isEmpty()) append(ext.uppercase(Locale.ROOT))
            }
            val id = idOf(url)
            urlById[id] = url
            val score = runCatching { o.get("score").asDouble }.getOrDefault(0.0) +
                runCatching { o.get("fingerprintf_score").asDouble }.getOrDefault(0.0) * 10
            hits.add(
                SubHit(
                    id = id,
                    title = title,
                    lang = second,
                    format = ext,
                    url = url,
                    score = score + if (ext == "srt") 1000.0 else 0.0
                )
            )
        }
        return hits.sortedByDescending { it.score }
    }

    /** 下载字幕文件内容（UTF-8 文本）；GBK 自动转码 */
    fun downloadFile(url: String): String {
        val bytes = Http.getBytes(url, api = false)
        return decodeSubtitle(bytes)
    }

    fun fileUrl(hit: SubHit): String = hit.url.ifEmpty { urlById[hit.id].orEmpty() }

    /** 兼容旧调用：按 id 取文件列表（迅雷直接给 url，包装成单文件） */
    fun files(@Suppress("UNUSED_PARAMETER") token: String, id: Long): List<SubFile> {
        val url = urlById[id] ?: return emptyList()
        val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "sub.srt" }
        return listOf(SubFile(name = name, url = url))
    }

    /** 兼容旧调用名 */
    fun detail(token: String, id: Long): List<SubFile> = files(token, id)

    fun playable(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ttml") || n.endsWith(".dfxp")
    }

    // ---------- helpers ----------

    private fun pickArray(body: String): com.google.gson.JsonArray? {
        val t = body.trim()
        return runCatching {
            val el = JsonParser.parseString(t)
            when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    sequenceOf("data", "list", "subtitle", "subtitles", "result")
                        .mapNotNull { k -> o.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }
                        .firstOrNull()
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun okExt(ext: String): Boolean =
        ext in setOf("srt", "vtt", "ttml", "dfxp", "ass", "ssa")

    private fun langOf(o: com.google.gson.JsonObject): String {
        val arr = o.get("languages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        return arr.mapNotNull {
            when {
                it.isJsonPrimitive -> it.asString
                it.isJsonObject -> it.asJsonObject.str("name", "desc", "lang")
                else -> null
            }
        }.filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun durText(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 60 -> "${min}分钟"
            else -> "${min / 60}小时${min % 60}分"
        }
    }

    private fun idOf(url: String): Long {
        var h = url.hashCode().toLong()
        if (h < 0) h = -h
        return h
    }

    fun cleanTitle(s: String): String {
        var t = s.trim()
        if (t.isEmpty()) return ""
        t = t.replace(Regex("(?i)\\.(mp4|mkv|avi|rmvb|ts|m3u8|flv|wmv|mov)$"), "")
        t = t.replace(
            Regex("(?i)[\\s._\\-\\[\\]()]*(1080p|720p|2160p|4k|8k|hdr|bluray|blu-ray|web-?dl|remux|h\\.?26[45]|hevc|x26[45]|[ch]hs|国语|中字|双语).*$"),
            " "
        )
        t = t.replace(Regex("[\\s._\\-]*第\\s*[0-9一二三四五六七八九十百]+\\s*[集话期回季].*$"), " ")
        t = t.replace(Regex("(?i)[\\s._\\-]*s[0-9]{1,2}\\s*e[0-9]{1,3}.*$"), " ")
        t = t.replace(Regex("(?i)[\\s._\\-]*ep\\s*[0-9]{1,3}.*$"), " ")
        t = t.replace(Regex("[\\s._\\-]+$"), "").replace(Regex("^[\\s._\\-]+"), "").trim()
        return t.ifEmpty { s.trim() }
    }

    private fun decodeSubtitle(bytes: ByteArray): String {
        // try UTF-8 strict
        try {
            val d = Charset.forName("UTF-8").newDecoder()
            d.onMalformedInput(CodingErrorAction.REPORT)
            d.onUnmappableCharacter(CodingErrorAction.REPORT)
            return d.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Throwable) {
        }
        for (cs in listOf("GB18030", "GBK")) {
            runCatching { return String(bytes, Charset.forName(cs)) }
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun com.google.gson.JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            if (has(k) && get(k).isJsonPrimitive) {
                val v = runCatching { get(k).asString }.getOrDefault("")
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }
}
