package com.bespectacledcyborg.remoteexplorer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroupModesTest {
    private lateinit var groupModes: GroupModes
    private val newest = FileIdentity(1, newestAdditionsPath)

    @Before
    fun setUp() {
        val preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("group_modes_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        groupModes = GroupModes(preferences)
    }

    @Test
    fun `nothing chosen is ungrouped`() {
        assertEquals(GroupMode.UNGROUPED, groupModes.modeAt(newest))
    }

    @Test
    fun `a choice is remembered for that folder only`() {
        groupModes.choose(newest, GroupMode.BY_DATE)

        assertEquals(GroupMode.BY_DATE, groupModes.modeAt(newest))
        assertEquals(GroupMode.UNGROUPED, groupModes.modeAt(FileIdentity(2, newestAdditionsPath)))
    }

    @Test
    fun `choosing ungrouped again forgets the choice`() {
        groupModes.choose(newest, GroupMode.BY_PARENT_FOLDER)
        groupModes.choose(newest, GroupMode.UNGROUPED)

        assertEquals(GroupMode.UNGROUPED, groupModes.modeAt(newest))
    }

    @Test
    fun `every choice is announced`() {
        val before = groupModes.changes.value

        groupModes.choose(newest, GroupMode.BY_DATE)

        assertNotEquals(before, groupModes.changes.value)
    }
}
