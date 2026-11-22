package com.summer.shotguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthAccessTest {
    private fun shot(id: String, scenes: Set<String> = setOf("cafe")) = Shot(
        id, "测试拍法", "", scenes, "stand", "portrait", "photo.jpg", "line.png", "", "", ""
    )

    @Test fun firstFiveTemplatesStayFree() {
        assertFalse(isShareLocked(shot("cafe-01"), emptySet()))
        assertFalse(isShareLocked(shot("cafe-05"), emptySet()))
    }

    @Test fun laterTemplatesRemainOpenWhileGatingIsDisabled() {
        val premium = shot("cafe-06")
        assertFalse(isShareLocked(premium, emptySet()))
        assertFalse(isShareLocked(premium, setOf("cafe")))
    }

    @Test fun unlockingAnyMatchingSceneMakesMultiSceneTemplateAvailable() {
        val premium = shot("travel-10", setOf("cafe", "travel"))
        assertFalse(isShareLocked(premium, emptySet()))
        assertFalse(isShareLocked(premium, setOf("travel")))
    }

    @Test fun rewardPrefersTheSceneTheUserWasBrowsing() {
        val item = shot("travel-08", setOf("cafe", "travel"))
        assertEquals("travel", rewardScene(item, "travel"))
        assertEquals("cafe", rewardScene(item, "unknown"))
    }
}
