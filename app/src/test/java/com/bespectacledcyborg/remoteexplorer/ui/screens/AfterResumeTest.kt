package com.bespectacledcyborg.remoteexplorer.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AfterResumeTest {

    class RecordingActivity : ComponentActivity() {
        val events = mutableListOf<String>()

        override fun onResume() {
            super.onResume()
            events += "onResume"
        }
    }

    @Test
    fun `a handoff made while the activity is coming back waits until onResume has run`() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).create().start()
        val activity = controller.get()

        activity.afterResumeStopsPlaybackService { activity.events += "handoff" }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<String>(), activity.events)

        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("onResume", "handoff"), activity.events)
    }

    @Test
    fun `a handoff made while already resumed runs straight away`() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).setup()
        val activity = controller.get()
        activity.events.clear()

        activity.afterResumeStopsPlaybackService { activity.events += "handoff" }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("handoff"), activity.events)
    }

    @Test
    fun `without an activity the handoff runs at once`() {
        val events = mutableListOf<String>()
        val noActivity: ComponentActivity? = null

        noActivity.afterResumeStopsPlaybackService { events += "handoff" }

        assertEquals(listOf("handoff"), events)
    }
}
