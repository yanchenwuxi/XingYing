package com.xing.tv

import com.xing.tv.api.SubtitleApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleApiTest {

    @Test
    fun cleanTitle_strips_quality_and_episode() {
        assertEquals("流浪地球", SubtitleApi.cleanTitle("流浪地球.1080p.BluRay.x264.mp4"))
        assertEquals("三体", SubtitleApi.cleanTitle("三体 第01集"))
        assertTrue(SubtitleApi.cleanTitle("S01E02 Demo").isNotEmpty())
    }

    @Test
    fun playable_formats() {
        assertTrue(SubtitleApi.playable("a.srt"))
        assertTrue(SubtitleApi.playable("A.VTT"))
        assertFalse(SubtitleApi.playable("x.ass"))
    }
}
