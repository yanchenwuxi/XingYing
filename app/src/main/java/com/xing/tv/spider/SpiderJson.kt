package com.xing.tv.spider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xing.tv.model.Category
import com.xing.tv.model.Episode
import com.xing.tv.model.PageResult
import com.xing.tv.model.PlayLine
import com.xing.tv.model.PlayString
import com.xing.tv.model.VodDetail
import com.xing.tv.model.VodItem

internal object SpiderJson {

    fun parseList(text: String, page: Int): PageResult {
        val root = parseObj(text) ?: return PageResult(emptyList(), page, page)
        val list = root.arr("list") ?: root.arr("data") ?: return PageResult(emptyList(), page, page)
        val items = list.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val id = o.str("vod_id", "id", "vodId")
            val name = o.str("vod_name", "name", "title")
            if (id.isEmpty() || name.isEmpty()) null
            else VodItem(
                vodId = id,
                vodName = name,
                vodPic = o.str("vod_pic", "pic", "img", "cover"),
                vodRemarks = o.str("vod_remarks", "remarks", "note", "msg"),
                vodYear = o.str("vod_year", "year"),
                vodArea = o.str("vod_area", "area"),
                typeId = o.str("type_id", "tid")
            )
        }
        val pagecount = root.int("pagecount", "pageCount", "totalpage").coerceAtLeast(1)
        val total = root.int("total", "totalcount")
        val pc = when {
            pagecount > 1 -> pagecount
            total > 0 && items.isNotEmpty() ->
                ((total + items.size - 1) / items.size).coerceAtLeast(page)
            else -> page
        }
        return PageResult(items, page, pc)
    }

    fun parseCategories(text: String): List<Category> {
        val root = parseObj(text) ?: return emptyList()
        val arr = root.arr("class") ?: root.arr("classes") ?: root.arr("type")
            ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val id = o.str("type_id", "id", "tid")
            val name = o.str("type_name", "name", "title")
            if (id.isEmpty() || name.isEmpty()) null else Category(id, name)
        }
    }

    fun parseDetail(text: String): VodDetail? {
        val root = parseObj(text) ?: return null
        val list = root.arr("list")
        val o = when {
            list != null && list.size() > 0 && list[0].isJsonObject -> list[0].asJsonObject
            root.has("vod_id") || root.has("id") -> root
            else -> return null
        }
        val id = o.str("vod_id", "id")
        val name = o.str("vod_name", "name")
        if (id.isEmpty() && name.isEmpty()) return null
        val item = VodItem(
            vodId = id.ifEmpty { name },
            vodName = name.ifEmpty { id },
            vodPic = o.str("vod_pic", "pic"),
            vodRemarks = o.str("vod_remarks", "remarks"),
            vodYear = o.str("vod_year", "year"),
            vodArea = o.str("vod_area", "area"),
            typeId = o.str("type_id", "tid")
        )
        val lines = PlayString.parseLines(
            o.str("vod_play_from", "play_from"),
            o.str("vod_play_url", "play_url")
        ).ifEmpty { parseEpisodeArray(o) }
        return VodDetail(
            item = item,
            vodActor = o.str("vod_actor", "actor"),
            vodDirector = o.str("vod_director", "director"),
            vodContent = o.str("vod_content", "content", "des"),
            lines = lines
        )
    }

    fun parsePlayer(text: String): String {
        val root = parseObj(text) ?: return text.trim()
        return root.str("url", "playUrl", "play_url").ifEmpty { text.trim() }
    }

    private fun parseEpisodeArray(o: JsonObject): List<PlayLine> {
        val arr = o.arr("episodes") ?: o.arr("vod_play") ?: return emptyList()
        val eps = arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val e = el.asJsonObject
            val n = e.str("name", "title", "n")
            val u = e.str("url", "playUrl", "u")
            if (n.isEmpty() || u.isEmpty()) null else Episode(n, u)
        }
        return if (eps.isEmpty()) emptyList() else listOf(PlayLine("默认", eps))
    }

    private fun parseObj(text: String): JsonObject? =
        runCatching {
            val el = JsonParser.parseString(text.trim())
            when {
                el.isJsonObject -> el.asJsonObject
                el.isJsonArray && el.asJsonArray.size() > 0 && el.asJsonArray[0].isJsonObject ->
                    JsonObject().also { it.add("list", el.asJsonArray) }
                else -> null
            }
        }.getOrNull()

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            if (has(k) && get(k).isJsonPrimitive) {
                val v = runCatching { get(k).asString }.getOrDefault("")
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }

    private fun JsonObject.int(vararg keys: String): Int {
        for (k in keys) {
            if (has(k) && get(k).isJsonPrimitive) {
                return runCatching { get(k).asInt }.getOrDefault(0)
            }
        }
        return 0
    }

    private fun JsonObject.arr(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null
}
