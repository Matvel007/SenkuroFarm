package com.example.senkurofarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun newerMinorVersionIsDetected() {
        assertTrue(AppUpdater.compareVersions("1.1.0", "1.0.0") > 0)
    }

    @Test
    fun versionPrefixDoesNotAffectComparison() {
        assertEquals(0, AppUpdater.compareVersions("v1.0.0", "1.0.0"))
    }

    @Test
    fun multiDigitVersionIsComparedNumerically() {
        assertTrue(AppUpdater.compareVersions("1.10.0", "1.9.9") > 0)
    }

    @Test
    fun missingPatchVersionEqualsZeroPatch() {
        assertEquals(0, AppUpdater.compareVersions("2.1", "2.1.0"))
    }
}
