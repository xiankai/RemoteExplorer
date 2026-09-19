package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.Date

@Database(
    entities = [
        Connection::class,
        LocalFile::class,
        LocalTag::class,
        FileTagRelation::class,
        SettingsEntity::class,
    ],
    exportSchema = true,
    // 9 carries no schema change: Connection.password is still one TEXT column. The bump
    // exists so EncryptSavedPasswords has somewhere to run, turning the plaintext already on
    // disk into ciphertext. See that class.
    version = 9,
    autoMigrations = [
        // 5 -> 6 changed nothing: both schemas carry identityHash
        // 6890da0f705249cab681d87c67f24ff7, so the version was bumped without a schema
        // change. Declaring it costs an empty generated migration and completes the chain
        // 5 -> 6 -> 7 -> 8, which is what lets the destructive fallback go.
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        // 8 adds Connection.port, defaulted to 445 so existing connections are unaffected.
        AutoMigration(from = 7, to = 8)
    ]
)
@TypeConverters(Converters::class, CredentialConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun localDatabaseDao(): LocalDatabaseDao
    abstract fun settingsDao(): SettingsDao
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

