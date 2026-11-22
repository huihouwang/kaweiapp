package com.summer.shotguide

import org.junit.Assert.*
import org.junit.Test

class ShotFilterTest {
    private fun shot(scenes: Set<String> = setOf("park", "travel")) = Shot(
        "example", "Example", "", scenes, "sit", "portrait", "photo.jpg", null, "", "", ""
    )
    @Test fun onePhotoCanBelongToMultipleScenes() {
        assertTrue(ShotFilter(scene = "park").matches(shot()))
        assertTrue(ShotFilter(scene = "travel").matches(shot()))
        assertFalse(ShotFilter(scene = "cafe").matches(shot()))
    }
    @Test fun filtersIntersectInsteadOfShowingUnrelatedPhotos() {
        assertTrue(ShotFilter("park", "sit", "portrait").matches(shot()))
        assertFalse(ShotFilter("park", "stand", "portrait").matches(shot()))
        assertFalse(ShotFilter("park", "sit", "landscape").matches(shot()))
    }
    @Test fun newSceneNeedsNoFilterCodeChange() {
        assertTrue(ShotFilter(scene = "beach").matches(shot(setOf("beach"))))
    }
    @Test fun uncategorizedPhotosRemainInAllOnly() {
        assertTrue(ShotFilter().matches(shot(emptySet())))
        assertFalse(ShotFilter(scene = "park").matches(shot(emptySet())))
    }
}
