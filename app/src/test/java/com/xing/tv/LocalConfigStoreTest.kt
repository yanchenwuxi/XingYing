package com.xing.tv

import com.xing.tv.data.LocalConfigStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 本地配置文件导入的纯逻辑：协议前缀、内容校验、文件命名。
 *
 * 这三段决定了「用户从文件管理器选一个 .json 进来」这条路径是安全的：
 * 校验挡住误选的图片/二进制，命名挡住同名覆盖，前缀挡住目录穿越。
 */
class LocalConfigStoreTest {

    // ---------- 协议前缀 ----------

    @Test
    fun `只有 local 前缀才算本地配置`() {
        assertTrue(LocalConfigStore.isLocal("local://a-1b8710a4.json"))
        assertFalse(LocalConfigStore.isLocal("http://example.com/config.json"))
        assertFalse(LocalConfigStore.isLocal("asset://demo.json"))
        assertFalse(LocalConfigStore.isLocal("local:/a.json"))
    }

    // ---------- 内容校验 ----------

    @Test
    fun `合法配置通过校验`() {
        LocalConfigStore.validateConfigText("""{"sites":[{"key":"a","type":1,"api":"http://x/a"}]}""")
        LocalConfigStore.validateConfigText("""{"urls":[{"url":"http://x/c.json","name":"c"}]}""")
        LocalConfigStore.validateConfigText("""{"lives":[{"name":"L","url":"http://x/live.txt"}]}""")
    }

    @Test
    fun `非 JSON 内容被拒绝并说明原因`() {
        // 误选了网页或图片是最常见的情形，错误信息要能让用户看懂
        val msg = mustReject("<!DOCTYPE html><html><body>hello</body></html>")
        assertTrue(msg, msg.contains("JSON"))

        val blank = mustReject("   ")
        assertTrue(blank, blank.contains("为空"))
    }

    @Test
    fun `缺少关键字段的 JSON 被拒绝`() {
        val msg = mustReject("""{"foo":1,"bar":2}""")
        assertTrue(msg, msg.contains("sites"))
    }

    @Test
    fun `带 BOM 的配置能通过校验`() {
        LocalConfigStore.validateConfigText("\uFEFF{\"sites\":[]}")
    }

    @Test
    fun `stores 多仓与顶层数组也被接受`() {
        LocalConfigStore.validateConfigText("""{"stores":[{"name":"s","url":"http://x/c.json"}]}""")
        LocalConfigStore.validateConfigText("""[{"key":"a","type":1,"api":"http://x/a"}]""")
    }

    @Test
    fun `带注释的配置能通过校验`() {
        LocalConfigStore.validateConfigText("// 注释\n{\"sites\":[]}")
    }

    private fun mustReject(text: String): String = try {
        LocalConfigStore.validateConfigText(text)
        fail("应当拒绝该内容: ${text.take(30)}")
        ""
    } catch (e: IllegalStateException) {
        e.message.orEmpty()
    }

    // ---------- 文件命名 ----------

    @Test
    fun `文件名保留中文并附内容哈希`() {
        val a = LocalConfigStore.fileName("鱼壳海豚.json", "content-a")
        assertTrue(a, a.startsWith("鱼壳海豚-"))
        assertTrue(a, a.endsWith(".json"))

        // 内容不同必须得到不同文件名，否则两次导入同名配置会互相覆盖
        val b = LocalConfigStore.fileName("鱼壳海豚.json", "content-b")
        assertNotEquals(a, b)
    }

    @Test
    fun `文件名剔除路径分隔符与点号`() {
        val n = LocalConfigStore.fileName("../../etc/passwd", "{}")
        assertFalse(n.contains("/"))
        assertFalse(n.contains("\\"))
        assertFalse(n.contains(".."))
    }

    @Test
    fun `没有可用名字时回落到 config`() {
        assertTrue(LocalConfigStore.fileName(null, "{}").startsWith("config-"))
        assertTrue(LocalConfigStore.fileName("///", "{}").startsWith("config-"))
        assertTrue(LocalConfigStore.fileName("...", "{}").startsWith("config-"))
    }

    @Test
    fun `超长文件名被截断但保留哈希`() {
        val n = LocalConfigStore.fileName("很长".repeat(60) + ".json", "{}")
        assertTrue("文件名过长: ${n.length}", n.length <= 60)
        assertTrue(n, n.endsWith(".json"))
    }
}
