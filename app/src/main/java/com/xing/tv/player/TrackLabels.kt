package com.xing.tv.player

/**
 * 轨道显示文本。
 *
 * 播放器枚举出来的轨道必须变成用户看得懂的标签：公开源里的 [label] 常常是空的，
 * 只留一个 BCP-47 / ISO 639 语种码（`chi`、`yue`、`und`……），直接照搬用户无从判断。
 * 提取成纯函数是为了能脱离播放器单测。
 */

/**
 * 单条轨道的显示文本。
 *
 * @param label 轨道自带的名字，优先使用
 * @param language BCP-47 语种码，没有名字时按 [languageName] 翻译
 * @param groupIndex 轨道所在 TrackGroup 的序号（从 0 起）
 * @param groupLen 该 TrackGroup 内的轨道数
 * @param indexInGroup 轨道在组内的序号（从 0 起）
 * @param multiGroup 同类轨道分属多个 TrackGroup。此时同名音轨会重复出现，
 *   必须在标签里补上分组序号才区分得开
 */
internal fun trackLabel(
    label: String?,
    language: String?,
    groupIndex: Int,
    groupLen: Int,
    indexInGroup: Int,
    multiGroup: Boolean
): String {
    val named = label?.takeIf { it.isNotBlank() }
        ?: language?.takeIf { it.isNotBlank() }?.let { languageName(it) }
    val base = named ?: if (groupLen > 1) "第 ${indexInGroup + 1} 条" else "默认"
    return if (multiGroup && named != null) "分组 ${groupIndex + 1} · $base" else base
}

/**
 * BCP-47 / ISO 639 常见语种的中文名。
 *
 * 同一门语言常以不同码表示：`chi`/`zho` 是 ISO 639-2 的bibliographic 与 terminology 两种写法，
 * `zh`/`zh-Hans` 则是 BCP-47。一律取首个子标签并小写后比对，未识别的原样返回。
 */
internal fun languageName(code: String): String = when (code.lowercase().substringBefore('-')) {
    "zh", "chi", "zho", "cmn" -> "中文"
    "yue" -> "粤语"
    "eng", "en" -> "英语"
    "jpn", "ja" -> "日语"
    "kor", "ko" -> "韩语"
    "rus", "ru" -> "俄语"
    "fra", "fre", "fr" -> "法语"
    "deu", "ger", "de" -> "德语"
    "spa", "es" -> "西语"
    "und" -> "未指定"
    else -> code
}
