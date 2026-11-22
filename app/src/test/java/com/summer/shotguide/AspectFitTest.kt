package com.summer.shotguide

import org.junit.Assert.*
import org.junit.Test

class AspectFitTest {
    @Test fun portraitInWideViewKeepsRatioAndLeavesSides() {
        val box = fitInside(1080f, 800f, 1279f, 1706f)
        assertEquals(800f, box.height, .01f)
        assertTrue(box.left > 0f)
        assertEquals(1279f / 1706f, box.width / box.height, .0001f)
    }
    @Test fun landscapeReferenceIsNeverStretchedToPortrait() {
        val box = fitInside(1080f, 1920f, 1600f, 900f)
        assertEquals(1080f, box.width, .01f)
        assertEquals(607.5f, box.height, .01f)
        assertTrue(box.top > 0f)
    }
    @Test fun sameRatioHasNoCropOrMargin() {
        val box = fitInside(600f, 800f, 1200f, 1600f)
        assertEquals(FitBox(0f, 0f, 600f, 800f), box)
    }
}
