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
        DirTagRelation::class,
        TaggedDirectory::class,
        TagIndexIngest::class,
        SettingsEntity::class,
        DirectorySync::class,
    ],
    exportSchema = true,
    // 9 carries no schema change: Connection.password is still one TEXT column. The bump
    // exists so EncryptSavedPasswords has somewhere to run, turning the plaintext already on
    // disk into ciphertext. See that class.
    //
    // 10 adds DirectorySync, which records when each directory was last reconciled against
    // the share. A new table with no bearing on existing rows, so it auto-migrates.
    //
    // 11 adds SettingsEntity.thumbnailCacheVolume, nullable — and NULL is exactly the value
    // that means "internal storage", so every existing row migrates to the behaviour it
    // already had.
    // 12 adds the three tables the rolled-up tag index needs: DirTagRelation and
    // TaggedDirectory for tags that belong to a gallery rather than a file, and
    // TagIndexIngest for how far each connection has read the log. All new, none touching an
    // existing row, so it auto-migrates — and an existing install comes out with no
    // ingest state, which reads as "never pulled" and starts from a full pull.
    version = 12,
    autoMigrations = [
        // 5 -> 6 changed nothing: both schemas carry identityHash
        // 6890da0f705249cab681d87c67f24ff7, so the version was bumped without a schema
        // change. Declaring it costs an empty generated migration and completes the chain
        // 5 -> 6 -> 7 -> 8, which is what lets the destructive fallback go.
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        // 8 adds Connection.port, defaulted to 445 so existing connections are unaffected.
        AutoMigration(from = 7, to = 8),
        // 9 -> 10 only creates the DirectorySync table. Existing LocalFile rows survive with
        // no sync record, which reads as "cached, never reconciled" — they show immediately
        // and are reconciled once on the next visit.
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12)
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

