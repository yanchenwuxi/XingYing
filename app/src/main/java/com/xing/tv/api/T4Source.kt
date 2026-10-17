package com.xing.tv.api

import com.xing.tv.model.PageResult
import com.xing.tv.model.TvConfig
import com.xing.tv.model.VodFilter
import java.net.URLEncoder

/**
 * T4 聚合采集源（type=4）。
 * 在 JSON 采集基础上：列表带 filter=true；播放走 play/flag 接口。
 */
class T4Source(site: TvConfig.Site) : JsonCmsSource(site) {

    override fun categoryFiltered(categoryId: String, page: Int, filter: VodFilter): PageResult {
        val area = filter.area
        val year = filter.year.let { if (it.endsWith("年代")) it.dropLast(2) else it }
        val cls = filter.cls
        val params = buildString {
            append("ac=detail&filter=true")
            if (categoryId.isNotEmpty()) append("&t=").append(categoryId)
            if (area.isNotEmpty()) append("&area=").append(enc(area))
            if (year.isNotEmpty()) append("&year=").append(enc(year))
            if (cls.isNotEmpty()) append("&class=").append(enc(cls))
            append("&pg=").append(page)
        }
        var result = parsePage(fetchJson(params), page)
        if (!filter.isEmpty() && result.items.isNotEmpty()) {
            result = localFilter(result, area, year)
        }
        return result
    }

    override fun search(keyword: String, page: Int): PageResult {
        val wd = URLEncoder.encode(keyword, "UTF-8")
        return parsePage(fetchJson("wd=$wd&ac=detail&quick=false&pg=$page"), page)
    }

    /**
     * T4 播放解析：?play=<id>&flag=<线路名>
     * 返回字段常见为 url / playUrl / data.url
     */
    fun play(flag: String, id: String): String {
        if (id.isBlank()) return ""
        return runCatching {
            val params = buildString {
                append("play=").append(URLEncoder.encode(id, "UTF-8"))
                if (flag.isNotEmpty()) append("&flag=").append(URLEncoder.encode(flag, "UTF-8"))
            }
            val root = fetchJson(params)
            when {
                root.has("url") && root.get("url").isJsonPrimitive -> root.get("url").asString
                root.has("playUrl") && root.get("playUrl").isJsonPrimitive -> root.get("playUrl").asString
                root.has("data") && root.get("data").isJsonObject -> {
                    val d = root.getAsJsonObject("data")
                    sequenceOf("url", "playUrl", "src")
                        .mapNotNull { k ->
                            d.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
                        }
                        .firstOrNull().orEmpty()
                }
                else -> ""
            }
        }.getOrDefault("")
    }
}
