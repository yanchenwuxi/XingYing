package com.xing.tv

import com.xing.tv.api.ConfigLoader
import com.xing.tv.model.SiteKind
import com.xing.tv.model.TvConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点能力分级与配置诊断。
 *
 * 起因：一份公开配置的 97 个站点全是 `type=3 + api=csp_*` 的 jar 爬虫。
 * 旧实现把非 py 的 type=3 一律当 macCMS JSON 处理，于是这些站点被当成采集接口去请求，
 * 界面上表现为一批永远转圈的站点、首页看起来像「解析失败」。
 * 现在必须能准确识别它们不可用，并给出可用性统计。
 */
class SiteSupportTest {

    private fun site(
        type: Int,
        api: String,
        jar: String = ""
    ) = TvConfig.Site(key = "k", name = "n", type = type, api = api, jar = jar)

    // ---------- 类型分级 ----------

    @Test
    fun `csp 爬虫归为 jar 源且可用`() {
        val s = site(type = 3, api = "csp_NiuLai")
        assertEquals(SiteKind.SPIDER_JAR, s.kind)
        val js = site(type = 3, api = "https://x/drpy2.min.js", ext = "https://x/rule.js")
        assertEquals(SiteKind.JS, js.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `声明了 jar 的站点即使 api 是地址也归为 jar 源`() {
        val s = site(type = 3, api = "http://example.com/api", jar = "http://x/spider.jar")
        assertEquals(SiteKind.SPIDER_JAR, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `python 源可用`() {
        val s = site(type = 3, api = "py_local3")
        assertEquals(SiteKind.PY, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `xml 采集可用`() {
        val s = site(type = 0, api = "http://example.com/api.php/provide/vod/xml")
        assertEquals(SiteKind.CMS_XML, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `json 采集可用`() {
        val s = site(type = 1, api = "http://example.com/api.php/provide/vod/at/json")
        assertEquals(SiteKind.CMS_JSON, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `t4 聚合可用`() {
        val s = site(type = 4, api = "http://example.com/api.php/provide/vod")
        assertEquals(SiteKind.T4, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `js 源可用`() {
        val s = site(type = 2, api = "http://example.com/a.js")
        assertEquals(SiteKind.JS, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `未声明 type 但给了 http 地址时按 json 采集试探`() {
        val s = site(type = 5, api = "https://example.com/api")
        assertEquals(SiteKind.CMS_JSON, s.kind)
        assertTrue(s.supported)
    }

    @Test
    fun `未知类型且不是地址时判为不可用`() {
        val s = site(type = 5, api = "csp_Something")
        assertEquals(SiteKind.UNKNOWN, s.kind)
        assertFalse(s.supported)
    }

    // ---------- 配置诊断 ----------

    @Test
    fun `纯爬虫配置统计正确且标记点播不可用`() {
        val json = """
            {"sites":[
               {"key":"a","name":"A","type":3,"api":"csp_A"},
               {"key":"b","name":"B","type":3,"api":"csp_B"}
             ],
             "lives":[{"name":"L","url":"http://x/live.txt"}]}
        """.trimIndent()

        val cfg = ConfigLoader.loadFromText(json)

        assertEquals(2, cfg.diag.totalSites)
        assertEquals(2, cfg.diag.supportedSites)
        assertEquals(2, cfg.diag.spiderJar)
        assertEquals(1, cfg.diag.lives)
        assertFalse(cfg.diag.noUsableSite)
        assertEquals(2, cfg.usableSites.size)
        // 原始列表仍保留，供诊断说明「一共有多少个」
        assertEquals(2, cfg.sites.size)
    }

    @Test
    fun `混合配置只放行可用站点`() {
        val json = """
            {"sites":[
               {"key":"jar","name":"J","type":3,"api":"csp_J"},
               {"key":"js","name":"S","type":2,"api":"http://x/a.js"},
               {"key":"py","name":"P","type":3,"api":"py_local3"},
               {"key":"xml","name":"X","type":0,"api":"http://x/vod/xml"}
             ]}
        """.trimIndent()

        val cfg = ConfigLoader.loadFromText(json)

        assertEquals(4, cfg.diag.totalSites)
        assertEquals(4, cfg.diag.supportedSites)
        assertEquals(1, cfg.diag.spiderJar)
        assertEquals(1, cfg.diag.jsSource)
        assertFalse(cfg.diag.noUsableSite)
        assertEquals(listOf("jar", "js", "py", "xml"), cfg.usableSites.map { it.key })
    }

    @Test
    fun `摘要与解释文案包含关键数字`() {
        val cfg = ConfigLoader.loadFromText(
            """{"sites":[{"key":"a","type":3,"api":"csp_A"}],
                "lives":[{"name":"L","url":"http://x/live.txt"}]}"""
        )
        val brief = cfg.diag.brief()
        assertTrue(brief, brief.contains("1 个站点"))
        assertTrue(brief, brief.contains("1 个 jar 爬虫"))
        assertTrue(brief, brief.contains("1 个直播源"))

        val explain = cfg.diag.explain()
        assertTrue(explain, explain.contains("点播"))
        assertTrue(explain, explain.contains("直播源 1 个"))
    }

    @Test
    fun `空配置不误报为点播不可用`() {
        val cfg = ConfigLoader.loadFromText("""{"sites":[]}""")
        assertFalse(cfg.diag.noUsableSite)
    }

    // ---------- 内容健壮性 ----------

    @Test(expected = IllegalStateException::class)
    fun `返回网页时给出可读错误而不是裸的 JSON 解析异常`() {
        // 有些站点按 UA 分流：浏览器 UA 回 HTML 落地页，okhttp UA 才回配置
        ConfigLoader.loadFromText("<!DOCTYPE html><html><body>x</body></html>")
    }

    @Test
    fun `带 BOM 的配置能正常解析`() {
        val cfg = ConfigLoader.loadFromText(
            "\uFEFF{\"sites\":[{\"key\":\"a\",\"name\":\"A\",\"type\":1,\"api\":\"http://x/api\"}]}"
        )
        assertEquals(1, cfg.sites.size)
        assertEquals(1, cfg.diag.supportedSites)
    }

    // ---------- 直播源地址 ----------

    @Test
    fun `指向本机的直播源计入依赖内置代理`() {
        val cfg = ConfigLoader.loadFromText(
            """{"sites":[],
                "lives":[
                  {"name":"A","url":"http://127.0.0.1:5266/iptv.m3u"},
                  {"name":"B","url":"http://live.example.com/a.m3u"}]}"""
        )
        assertEquals(2, cfg.diag.lives)
        assertEquals(1, cfg.diag.localLives)
        assertEquals(1, cfg.diag.usableLives)
        assertFalse(cfg.diag.livesAllLocal)
    }

    @Test
    fun `直播源全部指向本机时如实说明依赖内置代理`() {
        // 这是那份公开配置的真实形态：lives 指向 TVBox 自己起的 127.0.0.1 代理
        val cfg = ConfigLoader.loadFromText(
            """{"sites":[{"key":"a","type":3,"api":"csp_A"}],
                "lives":[{"name":"superiptv","url":"http://127.0.0.1:5266/iptv.m3u"}]}"""
        )
        assertTrue(cfg.diag.livesAllLocal)
        assertEquals(0, cfg.diag.usableLives)
        assertTrue(cfg.diag.explain().contains("内置代理"))
        assertTrue(cfg.diag.brief().contains("依赖内置代理"))
    }

    @Test
    fun `localhost 与回环地址同样被识别`() {
        val cfg = ConfigLoader.loadFromText(
            """{"sites":[],
                "lives":[
                  {"name":"A","url":"http://localhost:5266/a.m3u"},
                  {"name":"B","url":"http://[::1]:5266/b.m3u"}]}"""
        )
        assertEquals(2, cfg.diag.localLives)
        assertEquals(0, cfg.diag.usableLives)
    }
}
