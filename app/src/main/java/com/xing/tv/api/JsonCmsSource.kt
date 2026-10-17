package com.xing.tv.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.PlayString
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodFilter
import com.xing.tv.model.VodItem
import com.xing.tv.net.Http
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * macCMS JSON 采集源（type=1）。
 *
 * v1.3.0 适配要点：
 * - 配置未写 categories 时，向采集口探测 class[] 生成分类 Tab
 * - 筛选参数真正拼进请求；接口忽略时对结果做本地地区/年份过滤
 * - pagecount 缺失时用 total/limit 推算；请求异常返回空页不抛出
 */
open class JsonCmsSource(override val site: TvConfig.Site) : Source {

    protected open fun apiUrl(params: String): String {
        val base = site.api.trim()
        val sep = if (base.contains("?")) "&" else "?"
        return "$base$sep$params"
    }

    protected fun fetchJson(params: String): JsonObject {
        return runCatching {
            val text = Http.get(apiUrl(params), headers = site.headerMap, api = true)
            val trimmed = text.trim()
            val el = JsonParser.parseString(trimmed)
            when {
                el.isJsonObject -> el.asJsonObject
                el.isJsonArray -> JsonObject().apply { add("list", el) }
                else -> JsonObject()
            }
        }.getOrElse { JsonObject() }
    }

    override fun home(page: Int): PageResult = category("", page)

    override fun categories(): List<Category> {
        val declared = site.categories
        if (declared.isNotEmpty()) {
            return declared.mapIndexed { i, name -> Category((i + 1).toString(), name) }
        }
        return probeCategories()
    }

    override fun category(categoryId: String, page: Int): PageResult =
        categoryFiltered(categoryId, page, VodFilter())

    override val supportsFilter: Boolean get() = true

    override fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult {
        val area = filter.area
        val year = filter.year.let { if (it.endsWith("年代")) it.dropLast(2) else it }
        val cls = filter.cls
        val params = buildString {
            append(if (isT4()) "ac=detail&filter=true" else "ac=detail")
            if (categoryId.isNotEmpty()) append("&t=").append(categoryId)
            if (area.isNotEmpty()) append("&area=").append(enc(area))
            if (year.isNotEmpty()) append("&year=").append(enc(year))
            if (cls.isNotEmpty()) append("&class=").append(enc(cls))
            append("&pg=").append(page)
        }
        var result = parsePage(fetchJson(params), page)
        // 接口忽略筛选时，对本页做本地兜底
        if (!filter.isEmpty() && result.items.isNotEmpty()) {
            result = localFilter(result, area, year)
        }
        return result
    }

    override fun search(keyword: String, page: Int): PageResult {
        val wd = enc(keyword)
        // 多种常见参数顺序，哪个有 list 用哪个
        val tries = listOf(
            "ac=detail&wd=$wd&pg=$page",
            "wd=$wd&ac=detail&pg=$page",
            "ac=videolist&wd=$wd&pg=$page"
        )
        for (p in tries) {
            val r = parsePage(fetchJson(p), page)
            if (r.items.isNotEmpty()) return r
        }
        return PageResult(emptyList(), page, page)
    }

    override fun detail(vodId: String): VodDetail? {
        val root = fetchJson("ac=detail&ids=$vodId")
        val list = root.arr("list") ?: return null
        val first = list.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return parseDetail(first)
    }

    // ---------- 分类探测（缓存 5 分钟） ----------

    private fun probeCategories(): List<Category> {
        val key = site.key.ifEmpty { site.api }
        catCache[key]?.let { if (System.currentTimeMillis() - it.at < CAT_TTL) return it.cats }
        val roots = listOf("ac=detail&pg=1", "ac=videolist&pg=1", "ac=list&pg=1")
        for (q in roots) {
            val o = fetchJson(q)
            val cats = parseClassArray(o)
            if (cats.isNotEmpty()) {
                catCache[key] = CachedCats(System.currentTimeMillis(), cats)
                return cats
            }
        }
        return emptyList()
    }

    private fun parseClassArray(o: JsonObject): List<Category> {
        val arr = o.arr("class") ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val c = el.asJsonObject
            val id = c.str("type_id", "tid", "id")
            val name = c.str("type_name", "name", "title")
            if (id.isEmpty() || name.isEmpty()) null else Category(id, name)
        }
    }

    // ---------- 解析 ----------

    protected fun parsePage(root: JsonObject, page: Int): PageResult {
        val list = root.arr("list") ?: root.arr("data") ?: return PageResult(emptyList(), page, page)
        val items = list.mapNotNull { parseItem(it) }
        var pagecount = root.int("pagecount", 0)
        if (pagecount <= 0) {
            val total = root.int("total", 0)
            val limit = root.int("limit", 0).takeIf { it > 0 } ?: 20
            if (total > 0) pagecount = ((total + limit - 1) / limit).coerceAtLeast(1)
        }
        if (pagecount <= 0) pagecount = if (items.isEmpty()) page else page + 1
        return PageResult(items, page, pagecount.coerceAtLeast(page))
    }

    protected open fun parseItem(el: JsonElement): VodItem? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val id = o.str("vod_id", "id")
        val name = o.str("vod_name", "name")
        if (id.isEmpty() || name.isEmpty()) return null
        return VodItem(
            vodId = id,
            vodName = name,
            vodPic = o.str("vod_pic", "pic"),
            vodRemarks = o.str("vod_remarks", "vod_remark", "remarks"),
            vodYear = o.str("vod_year", "year"),
            vodArea = o.str("vod_area", "area"),
            typeId = o.str("type_id", "tid")
        )
    }

    protected open fun parseDetail(o: JsonObject): VodDetail {
        val item = parseItem(o) ?: return VodDetail(VodItem("", ""))
        val from = o.str("vod_play_from", "play_from", "from")
        val url = o.str("vod_play_url", "play_url", "url")
        return VodDetail(
            item = item.copy(
                vodYear = item.vodYear.ifEmpty { o.str("vod_year") },
                vodArea = item.vodArea.ifEmpty { o.str("vod_area") }
            ),
            vodActor = o.str("vod_actor", "actor"),
            vodDirector = o.str("vod_director", "director"),
            vodDesc = o.str("vod_blurb", "vod_content", "vod_desc", "desc"),
            vodContent = o.str("vod_content", "content"),
            lines = PlayString.parseLines(from, url)
        )
    }

    protected fun isT4(): Boolean = site.type == 4

    protected fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    protected fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            if (has(k) && get(k).isJsonPrimitive) {
                val v = runCatching { get(k).asString }.getOrDefault("")
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }

    protected fun JsonObject.int(key: String, def: Int): Int =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrDefault(def) else def

    protected fun JsonObject.arr(key: String) =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null

    companion object {
        private const val CAT_TTL = 5 * 60_000L
        private data class CachedCats(val at: Long, val cats: List<Category>)
        private val catCache = ConcurrentHashMap<String, CachedCats>()

        /** 接口忽略筛选时的本地兜底 */
        fun localFilter(r: PageResult, area: String, year: String): PageResult {
            if (area.isEmpty() && year.isEmpty()) return r
            val filtered = r.items.filter { v ->
                val okArea = area.isEmpty() || v.vodArea.contains(area) || area.contains(v.vodArea)
                val okYear = year.isEmpty() || v.vodYear.contains(year) || year.contains(v.vodYear.take(4))
                okArea && okYear
            }
            return if (filtered.size == r.items.size) r else r.copy(items = filtered)
        }
    }
}
