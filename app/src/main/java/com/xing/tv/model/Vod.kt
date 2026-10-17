package com.xing.tv.model

/**
 * 点播统一数据模型。
 * 列表项 / 详情 / 多线路剧集，均与 TVBox 采集协议字段对应。
 */
data class VodItem(
    val vodId: String,
    val vodName: String,
    val vodPic: String = "",
    val vodRemarks: String = "",
    val vodYear: String = "",
    val vodArea: String = "",
    val typeId: String = ""
)

data class Category(val id: String, val name: String)

/** 播放线路：name -> 剧集列表 */
data class PlayLine(
    val name: String,
    val episodes: List<Episode>
)

data class Episode(val name: String, val url: String)

data class VodDetail(
    val item: VodItem,
    val vodActor: String = "",
    val vodDirector: String = "",
    val vodDesc: String = "",
    val vodContent: String = "",
    val lines: List<PlayLine> = emptyList()
)

/** 列表页结果（含分页） */
data class PageResult(
    val items: List<VodItem>,
    val page: Int = 1,
    val pageCount: Int = 1
)

/**
 * 解析「线路A$$$线路B」+「第01集$url#第02集$url」这类播放串。
 * 兼容 macCMS 的 vod_play_from / vod_play_url 字段格式。
 */
object PlayString {

    fun parseLines(playFrom: String?, playUrl: String?): List<PlayLine> {
        if (playUrl.isNullOrBlank()) return emptyList()
        val froms = playFrom.orEmpty().split("$$$").map { it.trim() }
        val groups = playUrl.split("$$$")
        return groups.mapIndexed { index, group ->
            val name = froms.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "线路${index + 1}"
            val episodes = group.split("#").mapNotNull { seg ->
                if (seg.isBlank()) return@mapNotNull null
                val i = seg.indexOf('$')
                if (i <= 0) return@mapNotNull null
                Episode(seg.substring(0, i).trim(), seg.substring(i + 1).trim())
            }
            PlayLine(name, episodes)
        }.filter { it.episodes.isNotEmpty() }
    }
}
