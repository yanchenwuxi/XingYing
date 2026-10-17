package com.xing.tv.api

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xing.tv.model.SiteKind
import com.xing.tv.model.TvConfig
import com.xing.tv.net.Http
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * TVBox 配置加载器。
 * - 单仓：直接含 sites/parses/lives
 * - 多仓：顶层只有 urls[]，逐个拉取子配置并合并 sites/parses/lives
 */
object ConfigLoader {

    private const val CACHE_DIR = "config_cache"

    private val cache = ConcurrentHashMap<String, TvConfig>()

    @Volatile
    var lastSpiderJar: String? = null
        private set

    private const val TAG = "ConfigLoader"

    /**
     * 拉取配置。
     *
     * 成功后落盘；网络失败时回退到上次缓存，避免断网/接口抖动导致应用完全不可用。
     * @param force 跳过内存缓存强制重新拉取（下拉刷新、用户主动保存时用）
     */
    fun load(url: String, force: Boolean = false): TvConfig {
        // 「个性化设置 → 接口缓存」关掉时不读也不写缓存，每次进首页都重新拉
        val useCache = runCatching { com.xing.tv.data.Prefs.configCacheEnabled }.getOrDefault(true)
        if (!force && useCache) cache[url]?.let { return it }

        // 本地导入的配置已经落在应用私有目录，直接读文件，不走网络也不进 http 缓存
        if (url.startsWith(com.xing.tv.data.LocalConfigStore.PREFIX)) {
            val text = com.xing.tv.data.LocalConfigStore.read(url)
                ?: throw IllegalStateException("本地配置文件已不存在，请重新导入")
            return parseFlexible(text, url).also { cache[url] = it }
        }

        val text = try {
            Http.get(url, api = true).also { if (useCache) saveToDisk(url, it) }
        } catch (t: Throwable) {
            // 网络失败：退回上次成功的配置，并明确告知调用方这是缓存数据
            if (useCache) loadFromDisk(url)?.let { stale ->
                cache[url] = stale
                return stale
            }
            throw t
        }
        val cfg = parseFlexible(text, url)
        cache[url] = cfg
        return cfg
    }

    fun loadFromText(text: String): TvConfig = parseFlexible(text, null)

    /**
     * 自动识别：TVBox JSON 配置 / 纯直播 m3u(m3u8) 列表。
     * 纯直播时生成仅含一条 lives 的配置，点播站点为空。
     */
    private fun parseFlexible(text: String, baseUrl: String?): TvConfig {
        val trimmed = text.removePrefix("﻿").trim()
        if (looksLikeLivePlaylist(trimmed) && baseUrl != null && baseUrl.startsWith("http")) {
            val name = runCatching {
                java.net.URL(baseUrl).host.removePrefix("www.")
            }.getOrDefault("直播源")
            val cfg = TvConfig(
                lives = listOf(TvConfig.Live(name = name, url = baseUrl, type = 0))
            )
            return cfg.copy(diag = buildDiag(cfg))
        }
        return parse(text, baseUrl)
    }

    private fun looksLikeLivePlaylist(body: String): Boolean {
        val head = body.lineSequence().take(8).joinToString("\n")
        if (head.contains("#EXTM3U", ignoreCase = true)) return true
        if (head.contains("#EXTINF", ignoreCase = true)) return true
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size >= 3 && lines.take(5).all { it.startsWith("http") }) return true
        return false
    }

    /** 清空内存 + 磁盘配置缓存（设置页「清理接口配置缓存」） */
    fun clearCache() {
        cache.clear()
        runCatching {
            val dir = Http.cacheDir(CACHE_DIR)
            dir.listFiles()?.forEach { f -> runCatching { f.delete() } }
        }.onFailure { Log.w(TAG, "clear config cache failed: $it") }
    }

    /** 磁盘配置缓存占用字节数 */
    fun cacheBytes(): Long = runCatching {
        Http.cacheDir(CACHE_DIR).listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    private fun cacheFile(url: String): java.io.File {
        // url 可能含非法文件名字符，用其散列作文件名
        val name = Integer.toHexString(url.hashCode())
        return java.io.File(Http.cacheDir(CACHE_DIR), "$name.json")
    }

    private fun saveToDisk(url: String, text: String) {
        runCatching {
            cacheFile(url).writeText(text)
        }.onFailure { Log.w(TAG, "save config cache failed: $it") }
    }

    private fun loadFromDisk(url: String): TvConfig? =
        runCatching {
            val f = cacheFile(url)
            if (!f.exists() || f.length() == 0L) return null
            parse(f.readText(), url)
        }.getOrNull()

    private fun parse(text: String, baseUrl: String?): TvConfig {
        val body = sanitize(text)
        val root = parseJson(body)

        val sites = mutableListOf<TvConfig.Site>()
        val parses = mutableListOf<TvConfig.Parse>()
        val lives = mutableListOf<TvConfig.Live>()

        // 顶层直接是站点数组：部分手写配置省略了 sites 包装
        if (root.isJsonArray) {
            collectSites(root.asJsonArray, sites)
            val direct = TvConfig(sites = sites, parses = parses, lives = lives)
            return direct.copy(diag = buildDiag(direct))
        }
        if (!root.isJsonObject) throw IllegalStateException("接口返回的内容不是合法 JSON，无法解析")
        val obj = root.asJsonObject

        // 多仓：递归一层 urls；stores/storehouse 是另一种常见的多仓写法
        val subUrls = mutableListOf<String>()
        if (obj.has("urls") && obj.get("urls").isJsonArray) {
            for (u in obj.getAsJsonArray("urls")) {
                val url = when {
                    u.isJsonObject -> u.asJsonObject.str("url")
                    u.isJsonPrimitive -> u.asString
                    else -> ""
                }
                if (url.isNotEmpty()) subUrls.add(url)
            }
        }
        for (key in listOf("stores", "storehouse")) {
            val arr = if (obj.has(key) && obj.get(key).isJsonArray) obj.getAsJsonArray(key) else continue
            for (el in arr) {
                if (!el.isJsonObject) continue
                val url = el.asJsonObject.str("url")
                if (url.isNotEmpty()) subUrls.add(url)
            }
        }
        if (subUrls.isNotEmpty()) fetchSubsParallel(subUrls, sites, parses, lives)
        collect(obj, sites, parses, lives)

        if (sites.isEmpty()) {
            for (alt in listOf("site", "website", "站点")) {
                obj.arr(alt)?.let { collectSites(it, sites) }
            }
        }

        val spider = obj.str("spider").ifEmpty { obj.str("jar") }
        lastSpiderJar = if (spider.isNotEmpty()) {
            // TVBox 常见写法：url;md5;xxxxxxxx，下载时只取 URL 段
            val raw = spider.substringBefore(";").trim()
            resolveMaybeRelative(raw, baseUrl)
        } else null

        val wallpaper = obj.str("wallpaper")
        val config = TvConfig(
            sites = sites,
            parses = parses,
            lives = lives,
            wallpaper = wallpaper,
            urls = obj.strArr("urls")
        )
        // 诊断单独算，避免诊断逻辑渗进解析主流程
        return config.copy(diag = buildDiag(config))
    }


    /** 多仓子配置并行拉取，上限 6 线程，单仓超时 20s */
    private fun fetchSubsParallel(
        urls: List<String>,
        sites: MutableList<TvConfig.Site>,
        parses: MutableList<TvConfig.Parse>,
        lives: MutableList<TvConfig.Live>
    ) {
        if (urls.isEmpty()) return
        if (urls.size == 1) {
            fetchSub(urls[0], sites, parses, lives)
            return
        }
        val bags = java.util.concurrent.CopyOnWriteArrayList<Triple<List<TvConfig.Site>, List<TvConfig.Parse>, List<TvConfig.Live>>>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(urls.size.coerceAtMost(6))
        try {
            val futures = urls.map { url ->
                pool.submit {
                    val s = mutableListOf<TvConfig.Site>()
                    val p = mutableListOf<TvConfig.Parse>()
                    val l = mutableListOf<TvConfig.Live>()
                    fetchSub(url, s, p, l)
                    bags.add(Triple(s, p, l))
                }
            }
            futures.forEach { runCatching { it.get(20, java.util.concurrent.TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        bags.forEach { (s, p, l) ->
            s.forEach { site ->
                sites.removeAll { it.key == site.key }
                sites.add(site)
            }
            parses.addAll(p)
            lives.addAll(l)
        }
    }

    /** 拉取子配置并合并；单个子配置失败不影响其余（公开多仓里挂掉一两个很常见） */
    private fun fetchSub(url: String, sites: MutableList<TvConfig.Site>, parses: MutableList<TvConfig.Parse>, lives: MutableList<TvConfig.Live>) {
        runCatching {
            val sub = Http.get(url.trim(), api = true)
            val r = parseJson(sanitize(sub))
            if (r.isJsonObject) collect(r.asJsonObject, sites, parses, lives)
            else if (r.isJsonArray) collectSites(r.asJsonArray, sites)
        }
    }

    /**
     * 非标准 JSON 清洗的主入口（BOM 与 HTML 守卫、JSONP 解包、行注释与块注释 stripping）。
     * internal 以便本地文件校验复用同一套清洗逻辑。
     *
     * 注意：行内的双斜杠不碰，否则会误伤地址里的 http 协议头。
     */
    internal fun sanitize(text: String): String {
        // 去掉可能的 UTF-8 BOM，否则 Gson 会在第 1 列就报 malformed JSON
        var body = text.removePrefix("\uFEFF").trim()
        if (body.isEmpty()) throw IllegalStateException("接口返回内容为空")
        // 有些站点按 UA 分流，回一个 HTML 落地页；这里提前给出可读的原因
        // （真正修好靠 Http 对接口请求改用 TVBox UA，这条只是兜住剩余情况）
        if (body.startsWith("<")) {
            throw IllegalStateException("接口返回的是网页而不是配置 JSON，地址可能不对或该站只对 TVBox 客户端开放")
        }
        // 整段 Base64（部分非标准接口）
        if (!body.startsWith("{") && !body.startsWith("[") && body.length > 32 &&
            body.matches(BASE64_RE)
        ) {
            runCatching {
                val decoded = String(android.util.Base64.decode(body, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val t = decoded.trim()
                if (t.startsWith("{") || t.startsWith("[")) body = t
            }
        }
        // JSONP 包裹
        if (!body.startsWith("{") && !body.startsWith("[")) {
            val l = body.indexOf('(')
            val r = body.lastIndexOf(')')
            if (l >= 0 && r > l) body = body.substring(l + 1, r).trim()
        }
        // 前缀垃圾：截取第一个 JSON 对象/数组
        if (!body.startsWith("{") && !body.startsWith("[")) {
            val iObj = body.indexOf('{')
            val iArr = body.indexOf('[')
            val start = when {
                iObj < 0 && iArr < 0 -> -1
                iObj < 0 -> iArr
                iArr < 0 -> iObj
                else -> minOf(iObj, iArr)
            }
            if (start >= 0) {
                val open = body[start]
                val close = if (open == '{') '}' else ']'
                val end = body.lastIndexOf(close)
                if (end > start) body = body.substring(start, end + 1)
            }
        }
        val noLineComments = body.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        return BLOCK_COMMENT_RE.replace(noLineComments, "")
    }

    /**
     * 解析 JSON，失败时再试一次「去掉多余尾逗号」的版本。
     *
     * 尾逗号修复只做兜底而不直接清洗原文：正则分不清引号内外的逗号，
     * 直接清洗可能误伤 ext 里嵌套的 JSON 字符串；先按原文解析，
     * 实在过不去才放宽一次。
     */
    private fun parseJson(body: String): com.google.gson.JsonElement {
        runCatching { JsonParser.parseString(body) }.getOrNull()?.let { return it }
        val fixed = TRAILING_COMMA_RE.replace(body, "$1")
        return runCatching { JsonParser.parseString(fixed) }.getOrNull()
            ?: throw IllegalStateException("接口返回的内容不是合法 JSON，无法解析")
    }

    /** 统计可用/不可用站点，供 UI 说明「为什么这个配置用不了」 */
    private fun buildDiag(cfg: TvConfig): TvConfig.Diag {
        var jar = 0
        var js = 0
        var unknown = 0
        cfg.sites.forEach { s ->
            when (s.kind) {
                SiteKind.SPIDER_JAR -> jar++
                SiteKind.JS -> js++
                SiteKind.UNKNOWN -> unknown++
                else -> Unit
            }
        }
        return TvConfig.Diag(
            totalSites = cfg.sites.size,
            supportedSites = cfg.usableSites.size,
            spiderJar = jar,
            jsSource = js,
            unknownKind = unknown,
            lives = cfg.lives.size,
            localLives = cfg.lives.count { it.isLoopback },
            parses = cfg.parses.size
        )
    }

    private fun collect(
        root: JsonObject,
        sites: MutableList<TvConfig.Site>,
        parses: MutableList<TvConfig.Parse>,
        lives: MutableList<TvConfig.Live>
    ) {
        root.arr("sites")?.let { collectSites(it, sites) }
        root.arr("parses")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val url = o.str("url")
            if (url.isEmpty()) return@forEach
            parses.removeAll { it.name == o.str("name") }
            parses.add(TvConfig.Parse(o.str("name"), url, o.int("type", 0)))
        }
        root.arr("lives")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val url = o.str("url")
            if (url.isEmpty()) return@forEach
            lives.add(TvConfig.Live(o.str("name").ifEmpty { "直播" }, url, o.int("type", 0)))
        }
    }

    /** 站点数组归一（顶层数组与 sites 字段共用）；同 key 后加载的覆盖先加载的 */
    private fun collectSites(arr: JsonArray, sites: MutableList<TvConfig.Site>) {
        arr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val key = o.str("key")
            val api = o.str("api")
            if (key.isEmpty() || api.isEmpty()) return@forEach
            sites.removeAll { it.key == key }
            sites.add(
                TvConfig.Site(
                    key = key,
                    name = o.str("name").ifEmpty { key },
                    type = o.int("type", 1),
                    api = api,
                    // ext 可能是字符串、对象或数组；对象/数组需保留 JSON 文本给爬虫 init
                    ext = o.anyAsString("ext"),
                    jar = o.str("jar"),
                    searchable = o.int("searchable", 1),
                    quickSearch = o.int("quickSearch", 0),
                    filterable = o.int("filterable", 0),
                    categories = o.strArr("categories"),
                    playerType = o.int("playerType", -1),
                    header = o.str("header")
                )
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asString }.getOrDefault("") else ""

    /** 字段可能是字符串 / 对象 / 数组：统一成字符串（对象与数组保留 JSON 原文） */
    private fun JsonObject.anyAsString(key: String): String {
        if (!has(key) || get(key).isJsonNull) return ""
        val el = get(key)
        return when {
            el.isJsonPrimitive -> runCatching { el.asString }.getOrDefault("")
            else -> el.toString()
        }
    }

    private fun JsonObject.int(key: String, def: Int): Int =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrDefault(def) else def

    private fun JsonObject.strArr(key: String): List<String> {
        val a: JsonArray = arr(key) ?: return emptyList()
        return a.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
    }

    private fun JsonObject.arr(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private val BASE64_RE = Regex("^[A-Za-z0-9+/=\\s]+$")
    private val BLOCK_COMMENT_RE = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val TRAILING_COMMA_RE = Regex(",\\s*([}\\]])")

    /**
     * 非标准 JSON 清洗（注释/JSONP/BOM），供本地文件校验复用。
     * 尾逗号不在这里处理：正则分不清引号内外，误伤 ext 嵌套串的风险
     * 留给解析阶段的「失败再放宽」兜底。
     */
    private fun resolveMaybeRelative(path: String, baseUrl: String?): String {
        if (path.startsWith("http://") || path.startsWith("https://") ||
            path.startsWith("clan://") || path.startsWith("file://")
        ) return path
        if (baseUrl.isNullOrBlank()) return path
        return runCatching {
            baseUrl.toHttpUrl().resolve(path)?.toString() ?: path
        }.getOrDefault(path)
    }

    internal fun cleanForParse(text: String): String = sanitize(text)
}
