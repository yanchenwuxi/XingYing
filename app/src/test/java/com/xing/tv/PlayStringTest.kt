package com.xing.tv

import com.xing.tv.model.PlayString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放串解析单元测试。
 * 对应 macCMS 的 vod_play_from / vod_play_url 字段格式：
 *   线路：以 $$$ 分隔
 *   集数：以 # 分隔，每段形如「集名$地址」
 */
class PlayStringTest {

    @Test
    fun `单线路多集`() {
        val lines = PlayString.parseLines(
            "默认线路",
            "第01集\$http://a/1.mp4#第02集\$http://a/2.mp4"
        )
        assertEquals(1, lines.size)
        assertEquals("默认线路", lines[0].name)
        assertEquals(2, lines[0].episodes.size)
        assertEquals("第01集", lines[0].episodes[0].name)
        assertEquals("http://a/1.mp4", lines[0].episodes[0].url)
        assertEquals("第02集", lines[0].episodes[1].name)
        assertEquals("http://a/2.mp4", lines[0].episodes[1].url)
    }

    @Test
    fun `多线路多集`() {
        val lines = PlayString.parseLines(
            "线路一\$\$\$线路二",
            "第01集\$http://a/1.mp4#第02集\$http://a/2.mp4\$\$\$第01集\$http://b/1.mp4"
        )
        assertEquals(2, lines.size)
        assertEquals("线路一", lines[0].name)
        assertEquals(2, lines[0].episodes.size)
        assertEquals("线路二", lines[1].name)
        assertEquals(1, lines[1].episodes.size)
        assertEquals("http://b/1.mp4", lines[1].episodes[0].url)
    }

    @Test
    fun `线路数少于分组数时按序补齐名称`() {
        // from 只给了一个名字，第二组应兜底为「线路2」
        val lines = PlayString.parseLines(
            "唯一线路",
            "第01集\$http://a/1.mp4\$\$\$第01集\$http://b/1.mp4"
        )
        assertEquals(2, lines.size)
        assertEquals("唯一线路", lines[0].name)
        assertEquals("线路2", lines[1].name)
    }

    @Test
    fun `地址含多个美元符时只按第一个切分`() {
        val lines = PlayString.parseLines("线路", "第01集\$http://a/1.mp4?t=1&x=\$y")
        assertEquals("http://a/1.mp4?t=1&x=\$y", lines[0].episodes[0].url)
    }

    @Test
    fun `缺少播放地址返回空`() {
        assertTrue(PlayString.parseLines("线路", null).isEmpty())
        assertTrue(PlayString.parseLines("线路", "").isEmpty())
    }

    @Test
    fun `无美元符的片段被丢弃`() {
        val lines = PlayString.parseLines("线路", "坏片段#第01集\$http://a/1.mp4")
        assertEquals(1, lines[0].episodes.size)
        assertEquals("第01集", lines[0].episodes[0].name)
    }

    @Test
    fun `线路名为空时兜底`() {
        val lines = PlayString.parseLines("", "第01集\$http://a/1.mp4")
        assertEquals("线路1", lines[0].name)
    }

    @Test
    fun `全部片段无效时该线路被过滤`() {
        val lines = PlayString.parseLines("线路", "# #")
        assertTrue(lines.isEmpty())
    }
}
