package com.bespectacledcyborg.remoteexplorer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import com.bespectacledcyborg.remoteexplorer.data.db.Connection
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LastLocationTest {
    private lateinit var lastLocation: LastLocation
    private val nas = connection(id = 5)
    private val laptop = connection(id = 9)

    @Before
    fun setUp() {
        val preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("last_location_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        lastLocation = LastLocation(preferences)
    }

    @Test
    fun nothingRememberedRestoresNothing() {
        assertNull(lastLocation.resolve(listOf(nas, laptop)))
    }

    @Test
    fun restoresTheLastFolderOfTheLastConnection() {
        lastLocation.remember(5, "")
        lastLocation.remember(5, "gallery-dl/artist")

        assertEquals(Location(nas, "gallery-dl/artist"), lastLocation.resolve(listOf(nas, laptop)))
    }

    @Test
    fun aTagListingKeepsTheFolderItWasOpenedFrom() {
        lastLocation.remember(5, "gallery-dl")
        lastLocation.remember(5, "${tagPrefix}female:glasses")

        assertEquals("gallery-dl", lastLocation.resolve(listOf(nas))?.path)
    }

    @Test
    fun aDeletedConnectionIsForgottenNotReplaced() {
        lastLocation.remember(5, "gallery-dl")

        assertNull(lastLocation.resolve(listOf(laptop)))
        assertNull(lastLocation.resolve(listOf(nas, laptop)))
    }

    @Test
    fun forgettingAnotherConnectionKeepsThisOne() {
        lastLocation.remember(5, "gallery-dl")

        lastLocation.forget(9)
        assertEquals("gallery-dl", lastLocation.resolve(listOf(nas))?.path)

        lastLocation.forget(5)
        assertNull(lastLocation.resolve(listOf(nas)))
    }

    @Test
    fun backingOutForgetsTheLocation() {
        lastLocation.remember(5, "gallery-dl")

        lastLocation.forget()

        assertNull(lastLocation.resolve(listOf(nas)))
    }

    @Test
    fun theFolderStackWalksUpToTheRoot() {
        assertEquals(listOf("", "a", "a/b", "a/b/c"), Location(nas, "a/b/c").folderStack)
        assertEquals(listOf(""), Location(nas, "").folderStack)
    }

    private fun connection(id: Int) = Connection(
        id = id,
        name = "nas$id",
        hostname = "nas",
        port = 1445,
        username = "explorer",
        password = SecretText("secret"),
        domain = "",
        share = "media",
        lastModified = Date(0),
    )
}
