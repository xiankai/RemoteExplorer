package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bespectacledcyborg.remoteexplorer.data.crypto.ReversingCipher
import com.bespectacledcyborg.remoteexplorer.data.crypto.SecretText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * A pairing must go when either connection does. One left behind would map files onto a
 * connection id that no longer means anything — or, once the id is reused, onto a
 * different share entirely.
 */
@RunWith(RobolectricTestRunner::class)
class PairingDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addTypeConverter(CredentialConverters(ReversingCipher()))
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private val share = Connection(
        id = 1, name = "nas", hostname = "nas", username = "u", password = SecretText("p"),
        domain = "", share = "media", lastModified = Date(0),
    )
    private val phone = Connection(
        id = 3, name = "phone", type = ConnectionType.LOCAL, hostname = null, username = null,
        password = null, domain = null, share = null, localRoot = "/sdcard/Sync",
        lastModified = Date(0),
    )
    private val pairing = Pairing(3, "", 1, "Library")

    private suspend fun given() {
        db.connectionDao().addConnection(share)
        db.connectionDao().addConnection(phone)
        db.pairingDao().upsert(pairing)
    }

    @Test
    fun `deleting the local root deletes its pairing`() = runTest {
        given()
        db.connectionDao().deleteConnection(phone)
        assertEquals(emptyList<Pairing>(), db.pairingDao().getPairings().first())
    }

    @Test
    fun `deleting the share deletes the pairing too`() = runTest {
        given()
        db.connectionDao().deleteConnection(share)
        assertEquals(emptyList<Pairing>(), db.pairingDao().getPairings().first())
    }

    @Test
    fun `editing a connection keeps its pairing`() = runTest {
        // addConnection is an upsert. Were it a REPLACE, the row would be deleted and
        // re-inserted, and the cascade would take the pairing with it on every save.
        given()
        db.connectionDao().addConnection(phone.copy(name = "my phone"))
        assertEquals(listOf(pairing), db.pairingDao().getPairings().first())
    }
}
