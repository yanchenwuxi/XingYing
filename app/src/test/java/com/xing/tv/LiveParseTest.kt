package com.xing.tv

import com.xing.tv.api.LiveRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直播源格式解析。
 *
 * 三种落地格式必须都能归一成同样的分组结构，这是直播页能否列出频道的前提。
 */
class LiveParseTest {

    private val base = "http://live.example.com/dir/list.m3u"

    @Test
    fun `m3u 按 group-title 分组`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="1" group-title="央视",CCTV1
            http://a/1.m3u8
            #EXTINF:-1 group-title="央视",CCTV2
            http://a/2.m3u8
            #EXTINF:-1 group-title="卫视",湖南卫视
            http://a/3.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(2, groups.size)
        assertEquals("央视", groups[0].name)
        assertEquals(2, groups[0].channels.size)
        assertEquals("CCTV1", groups[0].channels[0].name)
        assertEquals("卫视", groups[1].name)
    }

    @Test
    fun `m3u 无分组时归到未分组`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Channel A
            http://a/a.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(1, groups.size)
        assertEquals("未分组", groups[0].name)
        assertEquals(1, groups[0].channels.size)
    }

    @Test
    fun `m3u 的 EXTGRP 覆盖 group-title`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="旧分组",Some
            #EXTGRP:新分组
            http://a/x.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        // EXTGRP 在地址行之前，因此分组以它为准
        assertEquals("新分组", groups[0].name)
    }

    @Test
    fun `分组文本按 genre 标记切分`() {
        val text = """
            央视,#genre#
            CCTV1,http://a/1.m3u8
            CCTV2,http://a/2.m3u8
            地方,#genre#
            湖南卫视,http://a/3.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(2, groups.size)
        assertEquals("央视", groups[0].name)
        assertEquals(2, groups[0].channels.size)
        assertEquals("湖南卫视", groups[1].channels[0].name)
    }

    @Test
    fun `分组文本中没有分组标记时归到未分组`() {
        val text = """
            卫视1,http://a/1.m3u8
            卫视2,http://a/2.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(1, groups.size)
        assertEquals("未分组", groups[0].name)
        assertEquals(2, groups[0].channels.size)
    }

    @Test
    fun `json 数组解析分组与频道`() {
        val text = """
            [
              {"name":"央视","list":[{"name":"CCTV1","url":"http://a/1.m3u8"}]},
              {"name":"卫视","list":[{"name":"湖南","url":"http://a/2.m3u8"}]}
            ]
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(2, groups.size)
        assertEquals("央视", groups[0].name)
        assertEquals("湖南", groups[1].channels[0].name)
    }

    @Test
    fun `json 包一层 lives 字段也能解析`() {
        val text = """
            {"lives":[{"name":"一组","list":[{"name":"ch1","url":"http://a/1.m3u8"}]}]}
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(1, groups.size)
        assertEquals("一组", groups[0].name)
        assertEquals(1, groups[0].channels.size)
    }

    @Test
    fun `相对地址按源地址补全基准`() {
        val text = """
            组,#genre#
            频道,relative/live.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals("http://live.example.com/dir/relative/live.m3u8", groups[0].channels[0].url)
    }

    @Test
    fun `协议相对地址补 http`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,X
            //cdn.example.com/x.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals("http://cdn.example.com/x.m3u8", groups[0].channels[0].url)
    }

    @Test
    fun `空白与非法内容返回空列表`() {
        assertTrue(LiveRepository.parse("   ", base).isEmpty())
        assertTrue(LiveRepository.parse("只有名字没有地址", base).isEmpty())
        assertTrue(LiveRepository.parse("""{"name":}""", base).isEmpty())
    }

    @Test
    fun `json 里缺 url 的频道会被跳过`() {
        val text = """
            [{"name":"g","list":[{"name":"a","url":"http://a/1.m3u8"},{"name":"b"}]}]
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals(1, groups.size)
        assertEquals(1, groups[0].channels.size)
    }

    @Test
    fun `m3u 的 tvg-logo 解析为台标地址`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://img.example.com/cctv1.png" group-title="央视",CCTV1
            http://a/1.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals("http://img.example.com/cctv1.png", groups[0].channels[0].logo)
    }

    @Test
    fun `没有 tvg-logo 时台标为空串而不是源地址`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV1
            http://a/1.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        // 空串若被当成相对地址补全，会错误地把播放列表地址当台标加载
        assertEquals("", groups[0].channels[0].logo)
    }

    @Test
    fun `json 的 logo 字段也解析并补全相对地址`() {
        val text = """
            [{"name":"g","list":[{"name":"ch","url":"http://a/1.m3u8","logo":"img/ch.png"}]}]
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals("http://live.example.com/dir/img/ch.png", groups[0].channels[0].logo)
    }

    @Test
    fun `分组文本没有台标字段`() {
        val text = """
            央视,#genre#
            CCTV1,http://a/1.m3u8
        """.trimIndent()

        val groups = LiveRepository.parse(text, base)

        assertEquals("", groups[0].channels[0].logo)
    }
}
