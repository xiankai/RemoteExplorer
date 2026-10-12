package com.bespectacledcyborg.remoteexplorer.ui.components

import com.bespectacledcyborg.remoteexplorer.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `a release build shows the version and commit only`() {
        assertEquals(
            "2026-10-13 07:30:00 · 30c5f69",
            appVersionLabel("2026-10-13 07:30:00", "30c5f69", "release"),
        )
    }

    @Test
    fun `any other build type is named after the commit`() {
        assertEquals(
            "2026-10-13 07:30:00 · 30c5f69 · releaseDebug",
            appVersionLabel("2026-10-13 07:30:00", "30c5f69", "releaseDebug"),
        )
    }

    @Test
    fun `the build carries the commit it was built from`() {
        assertTrue(BuildConfig.COMMIT_SHA.matches(Regex("[0-9a-f]{7,}")))
        assertTrue(appVersionLabel().startsWith("${BuildConfig.VERSION_NAME} · ${BuildConfig.COMMIT_SHA}"))
    }
}
