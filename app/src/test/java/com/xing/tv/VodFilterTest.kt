package com.xing.tv

import com.xing.tv.model.FilterOptions
import com.xing.tv.model.VodFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类筛选条件。
 *
 * 筛选参数直接拼进 macCMS 采集 URL，拼错会导致整页无数据，
 * 因此参数名与年代折算逻辑用单测锁住。
 */
class VodFilterTest {

    @Test
    fun `空筛选不产出参数`() {
        assertTrue(VodFilter().isEmpty())
        assertTrue(VodFilter().params().isEmpty())
        assertEquals("", VodFilter().describe())
    }

    @Test
    fun `参数名与采集协议一致`() {
        val p = VodFilter(area = "大陆", year = "2024", cls = "动作").params()

        assertEquals("大陆", p["area"])
        assertEquals("2024", p["year"])
        assertEquals("动作", p["class"])
    }

    @Test
    fun `摘要只含已选项`() {
        assertEquals("大陆·2024", VodFilter(area = "大陆", year = "2024").describe())
    }

    @Test
    fun `候选项非空且年份倒序`() {
        assertTrue(FilterOptions.years.first() >= FilterOptions.years.last())
        assertTrue(FilterOptions.areas.contains("大陆"))
        assertTrue(FilterOptions.classes.contains("动作"))
    }
}
