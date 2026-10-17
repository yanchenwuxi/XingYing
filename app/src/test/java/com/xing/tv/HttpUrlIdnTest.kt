package com.xing.tv

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 中文域名（IDN）兼容性回归测试。
 *
 * 背景：部分 TVBox 接口用中文域名，其前面的 CDN 只认 punycode 的 Host 头，
 * 收到原始 UTF-8 域名会直接回 400。OkHttp 的 [toHttpUrl] 会把 host 规范化成
 * punycode，请求头里就不会出现非 ASCII 字节。本用例锁住这一行为，
 * 避免将来替换网络层时悄悄退化。
 */
class HttpUrlIdnTest {

    @Test
    fun `中文域名规范化为 punycode`() {
        val url = "http://我不是.摸鱼儿.cc/".toHttpUrl()
        assertEquals("xn--ihqu10cn4c.xn--v4q818bf34b.cc", url.host)
        // 序列化后也必须是 punycode：真正写进 Host 请求头的是它
        assertEquals("http://xn--ihqu10cn4c.xn--v4q818bf34b.cc/", url.toString())
    }

    @Test
    fun `ASCII 域名保持原样`() {
        val url = "http://example.com/a/b?x=1".toHttpUrl()
        assertEquals("example.com", url.host)
    }

    @Test
    fun `带路径的中文域名同样规范化`() {
        val url = "https://我不是.摸鱼儿.cc/api.php/provide/vod".toHttpUrl()
        assertEquals("xn--ihqu10cn4c.xn--v4q818bf34b.cc", url.host)
        assertEquals("/api.php/provide/vod", url.encodedPath)
    }
}
