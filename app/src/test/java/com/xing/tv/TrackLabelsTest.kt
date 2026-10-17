package com.xing.tv

import com.xing.tv.player.languageName
import com.xing.tv.player.trackLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音轨 / 字幕轨标签的回归测试。
 *
 * 背景：公开源里的轨道 label 常常是空的，只有一个 BCP-47 / ISO 639 语种码，
 * 用户无法据此判断该选哪条。本用例锁住「名字 > 语种翻译 > 序号」的取值次序，
 * 以及多分组时补分组序号的行为。
 */
class TrackLabelsTest {

    @Test
    fun `轨道自带的名字优先于语种码`() {
        assertEquals("国语", trackLabel("国语", "chi", 0, 2, 0, multiGroup = false))
    }

    @Test
    fun `空白的名字不算名字`() {
        assertEquals("中文", trackLabel("   ", "chi", 0, 2, 0, multiGroup = false))
    }

    @Test
    fun `没有名字时按语种码翻译`() {
        assertEquals("粤语", trackLabel(null, "yue", 0, 2, 1, multiGroup = false))
    }

    @Test
    fun `既无名字也无语种码时用序号`() {
        assertEquals("第 2 条", trackLabel(null, null, 0, 2, 1, multiGroup = false))
    }

    @Test
    fun `只有一条轨道时不再编序号`() {
        assertEquals("默认", trackLabel(null, null, 0, 1, 0, multiGroup = false))
    }

    @Test
    fun `多分组时同名轨道补上分组序号`() {
        assertEquals("分组 2 · 国语", trackLabel("国语", null, 1, 2, 0, multiGroup = true))
    }

    @Test
    fun `多分组但轨道无名时不补分组序号`() {
        // 本来就是「第 n 条」这种相对表述，再叠一个分组序号只会更难读
        assertEquals("第 1 条", trackLabel(null, null, 1, 2, 0, multiGroup = true))
    }

    // ---------- languageName ----------

    @Test
    fun `常见的中文语种码统一映射为中文`() {
        assertEquals("中文", languageName("zh"))
        assertEquals("中文", languageName("chi"))
        assertEquals("中文", languageName("zho"))
        assertEquals("中文", languageName("cmn"))
    }

    @Test
    fun `粤语独立成条`() {
        assertEquals("粤语", languageName("yue"))
    }

    @Test
    fun `地区子标签不参与匹配`() {
        assertEquals("中文", languageName("zh-Hant"))
        assertEquals("英语", languageName("en-US"))
    }

    @Test
    fun `语种码大小写不敏感`() {
        assertEquals("日语", languageName("JPN"))
        assertEquals("英语", languageName("Eng"))
    }

    @Test
    fun `未收录的语种码原样返回`() {
        assertEquals("tha", languageName("tha"))
    }
}
