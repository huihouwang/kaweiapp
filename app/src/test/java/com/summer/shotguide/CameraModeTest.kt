package com.summer.shotguide

import androidx.camera.core.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeTest {
    @Test fun togglesBetweenFrontAndBack() {
        assertEquals(CameraSelector.LENS_FACING_FRONT, oppositeLensFacing(CameraSelector.LENS_FACING_BACK))
        assertEquals(CameraSelector.LENS_FACING_BACK, oppositeLensFacing(CameraSelector.LENS_FACING_FRONT))
    }

    @Test fun onlyFrontCaptureCanBeMirrored() {
        assertTrue(shouldMirrorCapture(CameraSelector.LENS_FACING_FRONT, true))
        assertFalse(shouldMirrorCapture(CameraSelector.LENS_FACING_FRONT, false))
        assertFalse(shouldMirrorCapture(CameraSelector.LENS_FACING_BACK, true))
    }

    @Test fun keepsRequestedLensWhenAvailable() {
        assertEquals(CameraSelector.LENS_FACING_FRONT, resolveLensFacing(CameraSelector.LENS_FACING_FRONT, true, true))
    }

    @Test fun fallsBackToOtherLens() {
        assertEquals(CameraSelector.LENS_FACING_BACK, resolveLensFacing(CameraSelector.LENS_FACING_FRONT, false, true))
    }

    @Test fun reportsNoCameraWhenNeitherLensExists() {
        assertNull(resolveLensFacing(CameraSelector.LENS_FACING_FRONT, false, false))
    }
}
