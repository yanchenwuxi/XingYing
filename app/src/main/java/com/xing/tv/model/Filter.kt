package com.xing.tv.model

/**
 * 分类下筛选条件。
 *
 * macCMS 采集协议（json/xml）的列表接口除 `t`（分类）/`pg`（页码）外，
 * 还接受 `area`（地区）/`year`（年份）/`class`（类型）三个筛选参数，
 * 与 TVBox 系客户端的筛选弹窗是同一套语义。
 */
data class VodFilter(
    val area: String = "",
    val year: String = "",
    val cls: String = ""
) {
    fun isEmpty(): Boolean = area.isEmpty() && year.isEmpty() && cls.isEmpty()

    /** 已选条件数量，用于底部筛选图标的角标 */
    fun selectedCount(): Int = listOf(area, year, cls).count { it.isNotEmpty() }

    /** 已选项摘要，用于筛选按钮与提示 */
    fun describe(): String =
        listOf(area, year, cls).filter { it.isNotEmpty() }.joinToString("·")

    /** 拼到采集 URL 上的参数表 */
    fun params(): Map<String, String> = buildMap {
        if (area.isNotEmpty()) put("area", area)
        if (year.isNotEmpty()) put("year", year)
        if (cls.isNotEmpty()) put("class", cls)
    }
}

/** 筛选候选项：年份取近 12 年，地区与类型用采集站最常见的叫法 */
object FilterOptions {
    val years: List<String> =
        (2026 downTo 2015).map { it.toString() } + listOf("2010年代", "2000年代")

    val areas: List<String> = listOf(
        "大陆", "香港", "台湾", "美国", "韩国", "日本", "英国", "法国", "德国", "泰国", "印度"
    )

    val classes: List<String> = listOf(
        "动作", "喜剧", "爱情", "科幻", "恐怖", "剧情", "战争", "犯罪",
        "悬疑", "奇幻", "冒险", "动画", "纪录", "综艺"
    )
}
