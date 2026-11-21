package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bespectacledcyborg.remoteexplorer.data.newestAdditionsPath

object RetireOldestFirst : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE FolderSort SET sort = 'ADDED_DESC' " +
                "WHERE path = ? AND sort IN ('LAST_MODIFIED_ASC', 'LAST_MODIFIED_DESC')",
            arrayOf(newestAdditionsPath),
        )
        db.execSQL("UPDATE FolderSort SET sort = 'LAST_MODIFIED_DESC' WHERE sort = 'LAST_MODIFIED_ASC'")
        db.execSQL("UPDATE settings SET defaultSort = 'LAST_MODIFIED_DESC' WHERE defaultSort = 'LAST_MODIFIED_ASC'")
    }
}
