package com.xing.tv.api

import com.google.gson.JsonParser
import com.xing.tv.model.LiveChannel
import com.xing.tv.model.LiveGroup
import com.xing.tv.model.TvConfig
import com.xing.tv.net.Http

/**
 * 直播源解析。
 *
 * TVBox 的 `lives` 里同一个 url 可能是三种完全不同的内容，实测按内容嗅探比按 type 判断更可靠
 * （很多配置源的 type 字段是随手写的）：
 *
 * 1. **m3u**：`#EXTM3U` 开头，`#EXTINF:[-1,]名称` 后跟地址，可带 `#EXTGRP:分组`
 * 2. **分组文本**：`分组名,#genre#` 行后跟 `名称,地址`
 * 3. **json**：`[{name, list:[{name, url}]}]`，也可能是包了一层的 `{data:[...]}` / `{lives:[...]}`
 *
 * 相对地址按源地址补全基准。
 */
object LiveRepository {

    private const val TAG = "LiveRepository"

    private val cache = HashMap<String, List<LiveGroup>>()

    /** 清空缓存（下拉刷新时调用） */
    @Synchronized
    fun invalidate() = cache.clear()

    fun load(live: TvConfig.Live, force: Boolean = false): List<LiveGroup> {
        if (!force) cache[live.url]?.let { return it }
        val text = when {
            live.url.startsWith("local-m3u://") -> {
                val name = live.url.removePrefix("local-m3u://")
                val f = java.io.File(com.xing.tv.App.instance.filesDir, "local_configs/$name")
                if (f.exists()) f.readText() else ""
            }
            else -> Http.get(live.url, api = true)
        }
        val groups = runCatching { parse(text, live.url) }
            .getOrElse {
                android.util.Log.w(TAG, "parse failed for ${live.url}: $it")
                emptyList()
            }
        cache[live.url] = groups
        return groups
    }

    /** 纯文本解析入口，供单元测试直接调用（不依赖网络） */
    internal fun parse(text: String, baseUrl: String): List<LiveGroup> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()
        if (body.startsWith("{") || body.startsWith("[")) {
            // json 解析失败（半截 JSON、字段类型不符等）时退回文本解析，
            // 而不是让异常一路抛到调用方导致整个直播页不可用
            return runCatching { parseJson(body, baseUrl) }
                .getOrElse { parseGenre(body, baseUrl) }
        }
        return if (body.contains("#EXTM3U", ignoreCase = true)) parseM3u(body, baseUrl)
        else parseGenre(body, baseUrl)
    }

    // ---------- m3u ----------

    private fun parseM3u(text: String, baseUrl: String): List<LiveGroup> {
        val groups = LinkedHashMap<String, MutableList<LiveChannel>>()
        var pendingName: String? = null
        var pendingGroup = ""
        var pendingLogo = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                // #EXTINF:-1 tvg-id="xx" tvg-logo="yy" group-title="zz",频道名
                pendingName = line.substringAfterLast(',').trim().ifEmpty { "未命名" }
                pendingGroup = extractAttr(line, "group-title").ifEmpty { "未分组" }
                pendingLogo = resolveMaybe(baseUrl, extractAttr(line, "tvg-logo"))
            } else if (line.startsWith("#", ignoreCase = true)) {
                // #EXTGRP:分组
                if (line.startsWith("#EXTGRP", ignoreCase = true)) {
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { pendingGroup }
                }
            } else {
                val name = pendingName ?: "未命名"
                val group = pendingGroup.ifEmpty { "未分组" }
                groups.getOrPut(group) { mutableListOf() }
                    .add(LiveChannel(name, resolve(baseUrl, line), group, pendingLogo))
                pendingName = null
                pendingGroup = ""
                pendingLogo = ""
            }
        }
        return groups.map { LiveGroup(it.key, it.value) }.filter { it.channels.isNotEmpty() }
    }

    private fun extractAttr(line: String, key: String): String {
        val re = Regex("""$key\s*=\s*"([^"]*)"""")
        return re.find(line)?.groupValues?.get(1)?.trim().orEmpty()
    }

    // ---------- 分组文本 ----------

    private fun parseGenre(text: String, baseUrl: String): List<LiveGroup> {
        val groups = LinkedHashMap<String, MutableList<LiveChannel>>()
        var current = "未分组"
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.contains("#genre#", ignoreCase = true)) {
                // 行形如 `央视,#genre#`，逗号属于分隔符而不是组名的一部分
                current = line.substringBefore("#genre#").trim().trimEnd(',').ifEmpty { "未分组" }
                groups.getOrPut(current) { mutableListOf() }
                continue
            }
            // 取最后一个逗号做分隔：频道名本身含逗号的情况很常见
            val name = line.substringBeforeLast(',').trim()
            val url = line.substringAfterLast(',').trim()
            val finalName: String
            val finalUrl: String
            when {
                // `名称,地址`：逗号后即地址，允许相对路径（由 resolve 补基准）
                url.isNotBlank() && url != line -> {
                    finalName = name
                    finalUrl = url
                }
                // 整行只有一个地址：用地址本身当频道名
                line.contains("://") -> {
                    finalName = line
                    finalUrl = line
                }
                // 没有逗号也没有协议的行不是频道，跳过
                else -> continue
            }
            if (finalName.isEmpty()) continue
            groups.getOrPut(current) { mutableListOf() }
                .add(LiveChannel(finalName, resolve(baseUrl, finalUrl), current))
        }
        return groups.map { LiveGroup(it.key, it.value) }.filter { it.channels.isNotEmpty() }
    }

    // ---------- json ----------

    private fun parseJson(text: String, baseUrl: String): List<LiveGroup> {
        val root = JsonParser.parseString(text)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val o = root.asJsonObject
                listOf("lives", "data", "list", "channels")
                    .firstNotNullOfOrNull { key -> o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
            }
            else -> null
        } ?: return parseGenre(text, baseUrl)

        val groups = mutableListOf<LiveGroup>()
        for (g in array) {
            if (!g.isJsonObject) continue
            val go = g.asJsonObject
            val gName = go.optString("name")
            val channels = go.get("list")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val list = mutableListOf<LiveChannel>()
            for (c in channels) {
                if (!c.isJsonObject) continue
                val co = c.asJsonObject
                val name = co.optString("name")
                val url = co.optString("url")
                if (url.isEmpty()) continue
                val logo = resolveMaybe(baseUrl, co.optString("logo").ifEmpty { co.optString("tvg-logo") })
                list.add(LiveChannel(name.ifEmpty { "未命名" }, resolve(baseUrl, url), gName, logo))
            }
            if (list.isNotEmpty()) {
                groups.add(LiveGroup(gName.ifEmpty { "未分组" }, list))
            }
        }
        return groups
    }

    private fun com.google.gson.JsonObject.optString(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

    /** 相对地址补全；协议相对地址补 http */
    private fun resolve(baseUrl: String, url: String): String {
        val u = url.trim()
        if (u.startsWith("//")) return "http:$u"
        if (u.contains("://")) return u
        return runCatching {
            java.net.URL(java.net.URL(baseUrl), u).toString()
        }.getOrDefault(u)
    }

    /**
     * 同 [resolve]，但空串直接返回空串。
     * 台标、封面这类字段常常整个缺失，不能让空串被补成源地址本身。
     */
    private fun resolveMaybe(baseUrl: String, url: String): String =
        if (url.isBlank()) "" else resolve(baseUrl, url)
}
