package com.xing.tv.model

/**
 * 直播频道模型。
 *
 * TVBox 配置里的 `lives` 有三种常见落地格式（m3u / 分组文本 / json），
 * 解析后统一归一到这里，界面层不再关心原始格式差异。
 */
data class LiveGroup(
    val name: String,
    val channels: List<LiveChannel>
)

data class LiveChannel(
    val name: String,
    val url: String,
    val group: String,
    /** 台标地址：m3u 的 `tvg-logo`、json 的 `logo`；没有则为空，界面回退为文字首字 */
    val logo: String = ""
) {
    /** 直播地址一般可直接播，无需经过采集站的 playerContent 解析 */
    val isDirect: Boolean get() = url.contains("://")
}
