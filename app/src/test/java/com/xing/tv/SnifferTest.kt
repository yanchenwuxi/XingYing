package com.xing.tv

import com.xing.tv.proxy.Sniffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嗅探器的纯逻辑测试。
 *
 * 只覆盖不发网络请求的判定路径（直链识别、直链原样返回）；
 * 需要抓取页面的部分依赖真实 HTTP，归入真机端到端验证。
 */
class SnifferTest {

    @Test
    fun `直链后缀识别`() {
        assertTrue(Sniffer.isDirect("http://a/v.m3u8"))
        assertTrue(Sniffer.isDirect("http://a/v.mp4"))
        assertTrue(Sniffer.isDirect("http://a/v.flv?token=1&t=2"))
        assertTrue(Sniffer.isDirect("http://a/v.mkv#frag"))
        assertTrue(Sniffer.isDirect("HTTPS://A/V.MP4"))
    }

    @Test
    fun `非直链地址不识别`() {
        assertFalse(Sniffer.isDirect("http://a/play?id=1"))
        assertFalse(Sniffer.isDirect("http://a/page.html"))
        assertFalse(Sniffer.isDirect("http://a/v.mp4.html"))  // 伪装后缀的页面
        assertFalse(Sniffer.isDirect("http://a/download"))
        assertFalse(Sniffer.isDirect(""))
    }

    @Test
    fun `直链地址原样返回不发请求`() {
        val url = "http://a/v.mp4?x=1"
        assertEquals(url, Sniffer.resolve(url))
        assertEquals("", Sniffer.resolve(""))
        assertEquals("", Sniffer.resolve("   "))
    }

    @Test
    fun `带锚点与查询串的直链不被截断`() {
        assertEquals("http://a/seg.ts?end=1", Sniffer.resolve("http://a/seg.ts?end=1"))
        assertEquals("http://a/index.m3u8#t=3", Sniffer.resolve("http://a/index.m3u8#t=3"))
    }
}
