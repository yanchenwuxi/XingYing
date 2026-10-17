package com.xing.tv.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xing.tv.model.Category
import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodFilter
import com.xing.tv.model.VodItem
import com.xing.tv.net.Http
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * macCMS XML 采集源（type=0）。
 * 请求约定：?ac=videolist&t=分类&pg=页码 / &wd=搜索 / &ids=详情
 * 返回结构：<rss><list><video><id/><name/><pic/>...<dl><dd flag="线路">集$地址#集$地址</dd></dl></video></list></rss>
 */
class XmlCmsSource(override val site: TvConfig.Site) : Source {

    private val categoryCache = ConcurrentHashMap<String, List<Category>>()

    private fun apiUrl(params: String): String {
        val base = site.api.trim()
        val sep = if (base.contains("?")) "&" else "?"
        return "$base$sep$params"
    }

    private fun fetch(params: String): String =
        Http.get(apiUrl(params), headers = site.headerMap, api = true)

    override fun home(page: Int): PageResult = category("", page)

    override fun categories(): List<Category> {
        val declared = site.categories
        if (declared.isNotEmpty()) return declared.mapIndexed { i, n -> Category((i + 1).toString(), n) }
        categoryCache["all"]?.let { return it }
        // 从首页 XML 的 <class><ty id=""> 名称 里取分类
        val result = runCatching {
            val xml = fetch("ac=videolist&pg=1")
            parseClasses(xml)
        }.getOrDefault(emptyList())
        categoryCache["all"] = result
        return result
    }

    override fun category(categoryId: String, page: Int): PageResult =
        categoryFiltered(categoryId, page, VodFilter())

    override val supportsFilter: Boolean get() = true

    override fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult {
        val area = filter.area
        val year = filter.year.let { if (it.endsWith("年代")) it.dropLast(2) else it }
        val params = buildString {
            append("ac=videolist")
            if (categoryId.isNotEmpty()) append("&t=").append(categoryId)
            filter.params().forEach { (k, v) ->
                val value = if (k == "year" && v.endsWith("年代")) v.dropLast(2) else v
                append("&").append(k).append("=").append(URLEncoder.encode(value, "UTF-8"))
            }
            append("&pg=").append(page)
        }
        val result = runCatching { parsePage(fetch(params), page) }
            .getOrElse { PageResult(emptyList(), page, page) }
        return if (!filter.isEmpty()) JsonCmsSource.localFilter(result, area, year) else result
    }

    override fun search(keyword: String, page: Int): PageResult {
        val wd = URLEncoder.encode(keyword, "UTF-8")
        return parsePage(fetch("ac=videolist&wd=$wd&pg=$page"), page)
    }

    override fun detail(vodId: String): VodDetail? {
        val xml = fetch("ac=videolist&ids=$vodId")
        val items = parseVideos(xml)
        return items.firstOrNull()
    }

    // ---------- XML 解析 ----------

    private fun parsePage(xml: String, page: Int): PageResult {
        val items = parseVideos(xml).map { it.item }
        return PageResult(items, page, page)
    }

    private fun parseClasses(xml: String): List<Category> {
        return runCatching {
            val parser = newPullParser()
            parser.setInput(xml.reader())
            val out = mutableListOf<Category>()
            var depth = 0
            var inClass = false
            while (true) {
                val ev = parser.next()
                if (ev == XmlPullParser.START_TAG) {
                    depth++
                    if (parser.name.equals("class", true)) inClass = true
                    if (inClass && parser.name.equals("ty", true)) {
                        val id = parser.getAttributeValue(null, "id") ?: parser.getAttributeValue("", "id") ?: ""
                        val name = parser.nextText()?.trim().orEmpty()
                        if (id.isNotEmpty() && name.isNotEmpty()) out.add(Category(id, name))
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (parser.name.equals("class", true)) inClass = false
                    depth--
                    if (depth < 0) break
                } else if (ev == XmlPullParser.END_DOCUMENT) break
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun parseVideos(xml: String): List<VodDetail> {
        return runCatching {
            val parser = newPullParser()
            parser.setInput(xml.reader())
            val out = mutableListOf<VodDetail>()
            while (true) {
                val ev = parser.next()
                if (ev == XmlPullParser.START_TAG && parser.name.equals("video", true)) {
                    parseVideo(parser)?.let { out.add(it) }
                } else if (ev == XmlPullParser.END_DOCUMENT) break
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 解析单个 <video> 节点，直到其结束标签。 */
    private fun parseVideo(parser: XmlPullParser): VodDetail? {
        val map = LinkedHashMap<String, String>()
        val dl = StringBuilder() // 收集 <dd flag> 原文
        var depth = 1
        var ddFlag = ""
        var inDl = false
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = parser.name
                    if (name.equals("dl", true)) inDl = true
                    if (inDl && name.equals("dd", true)) {
                        ddFlag = parser.getAttributeValue(null, "flag")
                            ?: parser.getAttributeValue("", "flag") ?: ""
                        val text = parser.nextText()?.trim().orEmpty()
                        dl.append(ddFlag).append('|').append(text).append('\n')
                        depth-- // nextText 已消费结束标签
                    } else if (!inDl) {
                        val text = parser.nextText()?.trim().orEmpty()
                        map[name] = text
                        depth--
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
        val id = map["id"] ?: map["vod_id"] ?: return null
        val name = map["name"] ?: map["vod_name"] ?: return null
        val lines = parseDd(dl.toString())
        val detail = VodDetail(
            item = VodItem(
                vodId = id,
                vodName = name,
                vodPic = map["pic"] ?: map["vod_pic"] ?: "",
                vodRemarks = map["state"] ?: map["note"] ?: "",
                vodYear = map["year"] ?: "",
                vodArea = map["area"] ?: "",
                typeId = map["tid"] ?: ""
            ),
            vodActor = map["actor"] ?: "",
            vodDirector = map["director"] ?: "",
            vodDesc = map["des"] ?: "",
            vodContent = map["des"] ?: "",
            lines = lines
        )
        return detail
    }

    /** dl/dd 原文 -> 播放线路（每行：线路名|集$地址#集$地址） */
    private fun parseDd(raw: String): List<com.xing.tv.model.PlayLine> {
        if (raw.isBlank()) return emptyList()
        val froms = mutableListOf<String>()
        val urls = mutableListOf<String>()
        raw.trim().lines().forEach { line ->
            if ('|' !in line) return@forEach
            val i = line.indexOf('|')
            froms.add(line.substring(0, i))
            urls.add(line.substring(i + 1))
        }
        return com.xing.tv.model.PlayString.parseLines(
            froms.joinToString("$$$"),
            urls.joinToString("$$$")
        )
    }

    companion object {
        fun newPullParser(): XmlPullParser =
            org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
    }
}
