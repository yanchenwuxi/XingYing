package com.xing.tv

import com.xing.tv.api.ConfigLoader
import com.xing.tv.model.TvConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析单元测试。
 * 只覆盖单仓（顶层直接含 sites）场景；多仓需网络请求，不在此测。
 */
class ConfigLoaderTest {

    private val sample = """
        {
          "wallpaper": "http://a/wall.jpg",
          "sites": [
            {"key": "s1", "name": "站点一", "type": 1, "api": "http://a/api.php",
             "searchable": 1, "quickSearch": 1, "filterable": 1,
             "categories": ["电影","连续剧"],
             "header": "{\"Cookie\":\"uid=1\",\"Referer\":\"http://ref/\"}"},
            {"key": "s2", "name": "XML源", "type": 0, "api": "http://b/api"},
            {"key": "dup", "name": "先加载", "type": 1, "api": "http://d/api"},
            {"key": "dup", "name": "后加载覆盖", "type": 3, "api": "py_x",
             "ext": "http://c/spider.py"}
          ],
          "parses": [{"name":"解析口","url":"http://a/jx/?url=","type":0}],
          "lives": [{"name":"直播","url":"http://a/live.m3u8","type":0}]
        }
    """.trimIndent()

    @Test
    fun `解析站点与字段`() {
        val cfg = ConfigLoader.loadFromText(sample)
        assertEquals("http://a/wall.jpg", cfg.wallpaper)
        assertTrue(cfg.sites.isNotEmpty())

        val s1 = cfg.sites.first { it.key == "s1" }
        assertEquals(1, s1.type)
        assertEquals("http://a/api.php", s1.api)
        assertEquals(1, s1.searchable)
        assertEquals(1, s1.quickSearch)
        assertEquals(1, s1.filterable)
        assertEquals(listOf("电影", "连续剧"), s1.categories)
    }

    @Test
    fun `同key后加载覆盖先加载`() {
        val cfg = ConfigLoader.loadFromText(sample)
        // dup 被第四条覆盖为 py 源，先加载的那条被丢弃
        val dup = cfg.sites.single { it.key == "dup" }
        assertEquals("后加载覆盖", dup.name)
        assertEquals(3, dup.type)
        assertEquals("py_x", dup.api)
        assertTrue(dup.isPy)
        assertEquals("http://c/spider.py", dup.ext)
    }

    @Test
    fun `整行注释与块注释被忽略`() {
        val cfg = ConfigLoader.loadFromText(
            """
            // 这是手写配置里的注释行
            {
              /* 块注释：站点列表 */
              "sites": [{"key":"a","type":1,"api":"http://x/a"}] // 行尾注释不碰，地址里的 http:// 不受影响
            }
            """.trimIndent()
        )

        assertEquals(1, cfg.sites.size)
        assertEquals("http://x/a", cfg.sites[0].api)
    }

    @Test
    fun `多余尾逗号放宽解析`() {
        val cfg = ConfigLoader.loadFromText(
            """{"sites": [{"key":"a","type":1,"api":"http://x/a",},],}"""
        )

        assertEquals(1, cfg.sites.size)
    }

    @Test
    fun `JSONP 包裹被解开`() {
        val cfg = ConfigLoader.loadFromText(
            """tvbox({"sites": [{"key":"a","type":1,"api":"http://x/a"}]});"""
        )

        assertEquals(1, cfg.sites.size)
    }

    @Test
    fun `顶层数组直接视为站点表`() {
        val cfg = ConfigLoader.loadFromText(
            """[{"key":"a","type":1,"api":"http://x/a"}]"""
        )

        assertEquals(1, cfg.sites.size)
        assertEquals("a", cfg.sites[0].name)
    }

    @Test
    fun `非法内容给出可读原因而不是原始异常`() {
        val msg = runCatching { ConfigLoader.loadFromText("not json at all") }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(msg, msg.contains("JSON"))
    }

    @Test
    fun `站点header字段被解析为请求头映射`() {
        val cfg = ConfigLoader.loadFromText(sample)
        val s1 = cfg.sites.single { it.key == "s1" }
        assertEquals("uid=1", s1.headerMap["Cookie"])
        assertEquals("http://ref/", s1.headerMap["Referer"])
    }

    @Test
    fun `解析口与直播被解析`() {
        val cfg = ConfigLoader.loadFromText(sample)
        assertEquals(1, cfg.parses.size)
        assertEquals("http://a/jx/?url=", cfg.parses[0].url)
        assertEquals(1, cfg.lives.size)
        assertEquals("直播", cfg.lives[0].name)
    }

    @Test
    fun `缺少key或api的站点被跳过`() {
        val cfg = ConfigLoader.loadFromText(
            """{"sites":[{"name":"无key","api":"http://a"},{"key":"k","name":"无api"}]}"""
        )
        assertTrue(cfg.sites.isEmpty())
    }

    @Test
    fun `空配置不崩溃`() {
        val cfg = ConfigLoader.loadFromText("{}")
        assertTrue(cfg.sites.isEmpty())
        assertTrue(cfg.parses.isEmpty())
        assertTrue(cfg.lives.isEmpty())
    }

    @Test
    fun `站点header非法JSON时返回空映射`() {
        val site = TvConfig.Site("k", "n", 1, "http://a", header = "not a json")
        assertTrue(site.headerMap.isEmpty())
        // 空串同样返回空映射
        assertTrue(TvConfig.Site("k", "n", 1, "http://a").headerMap.isEmpty())
    }

    @Test
    fun `isPy仅对py前缀的脚本源成立`() {
        assertTrue(TvConfig.Site("k", "n", 3, "py_spider").isPy)
        assertTrue(TvConfig.Site("k", "n", 3, "csp_Blibili").isScript)
        assertTrue(!TvConfig.Site("k", "n", 3, "csp_Blibili").isPy)
        assertTrue(!TvConfig.Site("k", "n", 1, "http://a").isPy)
    }
}
