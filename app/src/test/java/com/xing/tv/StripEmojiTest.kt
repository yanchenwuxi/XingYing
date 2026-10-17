package com.xing.tv

import com.xing.tv.ui.stripEmoji
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 直播源 / 站点名去表情的回归测试。
 *
 * 背景：公开配置里的名称常写成 `🐬各大源合集 海豚影视永久免费…`，配上宽度截断
 * 会留下一个占位的豆腐块，因此先清掉 emoji 再截断。
 *
 * emoji 在 UTF-16 中一律是代理对（U+D800–DFFF），该区间不存在合法的独立字符，
 * 所以按码元区间跳过不会误伤正常文本——本用例锁住这一点。
 */
class StripEmojiTest {

    @Test
    fun `去掉开头的代理对表情`() {
        // 🐬 = U+1F428 = \uD83D\uDC28
        assertEquals("各大源合集", "🐬各大源合集".stripEmoji())
    }

    @Test
    fun `去掉中间的代理对表情`() {
        // 🎬 = U+1F3AC = \uD83C\uDFAC
        assertEquals("午夜剧场", "午夜🎬剧场".stripEmoji())
    }

    @Test
    fun `整个名字都是表情时得到空串`() {
        assertEquals("", "🐬".stripEmoji())
    }

    @Test
    fun `没有表情时原样返回`() {
        assertEquals("央视一套", "央视一套".stripEmoji())
    }

    @Test
    fun `中文与全角标点保持不变`() {
        assertEquals("湖南卫视（高清）", "湖南卫视（高清）".stripEmoji())
    }

    @Test
    fun `去掉零宽连接符拼合的多表情`() {
        // 👨‍👩‍👧‍👦 = 四个代理对表情，中间用 ZWJ(U+200D) 连接
        assertEquals("家庭", "👨‍👩‍👧‍👦家庭".stripEmoji())
    }

    @Test
    fun `去掉变体选择符`() {
        // ❤️ = U+2764(U+FE0F)：正文字符保留，只清掉变体选择符
        assertEquals("❤ 爱心", "❤️ 爱心".stripEmoji())
    }

    @Test
    fun `孤立的低位代理也不会留下残缺字符`() {
        // 正常解码不会产生孤立代理，但防御性地保证不崩溃、不留下半个字符
        assertEquals("abc", "a\uD800bc".stripEmoji())
    }
}
