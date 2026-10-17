package com.xing.tv.py

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xing.tv.App
import com.xing.tv.api.Source
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Python 脚本源（type=3 且 api 形如 py_xxx）。
 * 所有数据请求交给脚本内的 homeContent/categoryContent/... 完成，
 * 本类只负责加载脚本并把 JSON 结果映射到模型。
 *
 * homeContent 结果按 siteKey 短期缓存：分类目录与首页列表都要用它，
 * 若不缓存，每次进首页会对脚本发起两次相同的 homeContent 调用。
 */
class PySource(override val site: TvConfig.Site) : Source {

    private val appContext: Context get() = App.instance

    private val gson = com.google.gson.Gson()

    private fun ensureLoaded(): Boolean {
        if (PyBridge.ready(site.key)) return true
        val ok = PyBridge.load(appContext, site.key, site.api, site.ext)
        if (!ok) {
            android.util.Log.e("PySource", "load failed ${site.key}: ${PyBridge.lastError}")
            lastLoadError = PyBridge.lastError
        } else {
            lastLoadError = ""
        }
        return ok
    }

    @Volatile
    private var lastLoadError: String = ""

    private fun callJson(method: String, vararg args: Any?): JsonObject {
        if (!ensureLoaded()) {
            throw IllegalStateException(
                lastLoadError.ifBlank { "Python 脚本加载失败（${site.api}）" }
            )
        }
        val text = PyBridge.call(site.key, method, *args)
        if (text.isBlank()) return JsonObject()
        return runCatching {
            val el = JsonParser.parseString(text.trim())
            when {
                el.isJsonObject -> el.asJsonObject
                el.isJsonArray -> JsonObject().apply { add("list", el) }
                else -> JsonObject()
            }
        }.getOrDefault(JsonObject())
    }

    // ---------- homeContent 缓存 ----------

    private class Cached(val at: Long, val json: JsonObject)

    private fun homeContentCached(): JsonObject {
        val now = System.currentTimeMillis()
        homeCache[site.key]?.let { if (now - it.at < HOME_TTL_MS) return it.json }
        return callJson("homeContent", true).also { json ->
            if (json.size() > 0) homeCache[site.key] = Cached(now, json)
        }
    }

    override fun home(page: Int): PageResult {
        // 首页推荐位：第 1 页走 homeContent（带缓存）；
        // 无限滚动的后续页改走 categoryContent，否则永远重复第一页数据。
        if (page <= 1) return parseList(homeContentCached(), 1)
        return category("", page)
    }

    override fun categories(): List<Category> {
        val root = homeContentCached()
        val arr = when {
            root.has("class") && root.get("class").isJsonArray -> root.getAsJsonArray("class")
            root.has("list") && root.get("list").isJsonArray -> root.getAsJsonArray("list")
            else -> return emptyList()
        }
        return arr.mapNotNull {
            if (!it.isJsonObject) return@mapNotNull null
            val o = it.asJsonObject
            val id = o.str("type_id", "id")
            val name = o.str("type_name", "name")
            if (id.isEmpty() || name.isEmpty()) null else Category(id, name)
        }
    }

    override fun category(categoryId: String, page: Int): PageResult {
        val root = callJson("categoryContent", categoryId, page, true, "")
        return parseList(root, page)
    }

    override fun search(keyword: String, page: Int): PageResult {
        val root = callJson("searchContent", keyword, false)
        return parseList(root, page)
    }

    override fun detail(vodId: String): VodDetail? {
        // 明确传 JSON 字符串，app.py 的 _to_ids 负责归一化（list/字符串均可）
        val root = callJson("detailContent", gson.toJson(listOf(vodId)))
        val arr = when {
            root.has("list") && root.get("list").isJsonArray -> root.getAsJsonArray("list")
            root.has("videos") && root.get("videos").isJsonArray -> root.getAsJsonArray("videos")
            else -> return null
        }
        val first = arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return parseDetail(first)
    }

    /** py 源播放解析：playerContent(flag, id, ext) -> {parse, url, ...} */
    fun player(flag: String, id: String): Pair<Int, String> {
        val root = callJson("playerContent", flag, id, "")
        val url = root.str("url")
        val parse = runCatching { root.get("parse").asInt }.getOrDefault(if (url.contains("http")) 0 else 1)
        return parse to url
    }

    /**
     * py 源本地代理：localProxy(params) -> [status, contentType, body, headers, isBase64]。
     * 脚本未实现或调用失败时返回 null。
     */
    fun localProxy(paramsJson: String): LocalProxyResult? {
        if (!ensureLoaded()) return null
        val text = PyBridge.call(site.key, "localProxy", paramsJson)
        if (text.isBlank()) return null
        return runCatching {
            val el = JsonParser.parseString(text.trim())
            if (!el.isJsonArray) return null
            val arr = el.asJsonArray
            if (arr.size() < 3) return null
            val status = runCatching { arr[0].asInt }.getOrDefault(404)
            val contentType = runCatching { arr[1].asString }.getOrDefault("text/plain")
            val bodyStr = runCatching { arr[2].asString }.getOrDefault("")
            // isBase64 位于末位；脚本可能只返回 3 元素，此时按文本处理
            val isBase64 = runCatching { arr[arr.size() - 1].asInt }.getOrDefault(0)
            val body = if (isBase64 == 1) {
                android.util.Base64.decode(bodyStr, android.util.Base64.DEFAULT)
            } else {
                bodyStr.toByteArray(Charsets.UTF_8)
            }
            val headers = mutableMapOf<String, String>()
            if (arr.size() >= 4 && arr[3].isJsonObject) {
                arr[3].asJsonObject.entrySet().forEach { (k, v) ->
                    runCatching { headers[k] = v.asString }
                }
            }
            LocalProxyResult(status, contentType, body, headers)
        }.getOrNull()
    }

    // ---------- 映射 ----------

    private fun parseList(root: JsonObject, page: Int): PageResult {
        val arr = when {
            root.has("list") && root.get("list").isJsonArray -> root.getAsJsonArray("list")
            root.has("results") && root.get("results").isJsonArray -> root.getAsJsonArray("results")
            else -> return PageResult(emptyList(), page, page)
        }
        val items = arr.mapNotNull {
            if (!it.isJsonObject) return@mapNotNull null
            val o = it.asJsonObject
            val id = o.str("vod_id", "id")
            val name = o.str("vod_name", "name", "title")
            if (id.isEmpty() || name.isEmpty()) null else VodItem(
                vodId = id,
                vodName = name,
                vodPic = o.str("vod_pic", "pic", "cover"),
                vodRemarks = o.str("vod_remarks", "vod_remark", "remarks", "note"),
                vodYear = o.str("vod_year", "year"),
                vodArea = o.str("vod_area", "area"),
                typeId = o.str("type_id", "tid")
            )
        }
        val pagecount = runCatching { root.get("pagecount").asInt }.getOrDefault(page)
        return PageResult(items, page, pagecount.coerceAtLeast(page))
    }

    private fun parseDetail(o: JsonObject): VodDetail {
        val item = VodItem(
            vodId = o.str("vod_id", "id"),
            vodName = o.str("vod_name", "name", "title"),
            vodPic = o.str("vod_pic", "pic", "cover"),
            vodRemarks = o.str("vod_remarks", "vod_remark", "remarks"),
            vodYear = o.str("vod_year", "year"),
            vodArea = o.str("vod_area", "area"),
            typeId = o.str("type_id", "tid")
        )
        val lines = com.xing.tv.model.PlayString.parseLines(
            o.str("vod_play_from", "play_from", "from"),
            o.str("vod_play_url", "play_url", "url")
        ).ifEmpty {
            // 有的脚本直接给 episodes 结构
            parseEpisodeArray(o)
        }
        return VodDetail(
            item = item,
            vodActor = o.str("vod_actor", "actor"),
            vodDirector = o.str("vod_director", "director"),
            vodDesc = o.str("vod_blurb", "vod_desc", "vod_content", "desc"),
            vodContent = o.str("vod_content", "content"),
            lines = lines
        )
    }

    private fun parseEpisodeArray(o: JsonObject): List<com.xing.tv.model.PlayLine> {
        if (!o.has("episodes") || !o.get("episodes").isJsonArray) return emptyList()
        val arr = o.getAsJsonArray("episodes")
        val episodes = arr.mapNotNull {
            if (!it.isJsonObject) return@mapNotNull null
            val e = it.asJsonObject
            val name = e.str("name", "title")
            val url = e.str("url", "play_url")
            if (name.isEmpty() || url.isEmpty()) null else com.xing.tv.model.Episode(name, url)
        }
        return if (episodes.isEmpty()) emptyList()
        else listOf(com.xing.tv.model.PlayLine("默认", episodes))
    }

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            if (has(k) && !get(k).isJsonNull) {
                val v = runCatching { get(k).asString }.getOrNull()
                if (!v.isNullOrEmpty()) return v
            }
        }
        return ""
    }

    companion object {
        /**
         * homeContent 缓存有效期：够覆盖「分类目录 + 首页列表」这一组连续调用即可
         */
        private const val HOME_TTL_MS = 60_000L

        private val homeCache = ConcurrentHashMap<String, Cached>()

        /** 丢弃缓存（下拉刷新、配置变更时调用） */
        @JvmStatic
        fun invalidate(siteKey: String? = null) {
            if (siteKey == null) homeCache.clear() else homeCache.remove(siteKey)
        }
    }
}

/**
 * localProxy 返回值。
 * 对应脚本 `[status, contentType, body, headers, isBase64]` 约定。
 */
data class LocalProxyResult(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalProxyResult) return false
        return status == other.status &&
                contentType == other.contentType &&
                body.contentEquals(other.body) &&
                headers == other.headers
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + contentType.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}
