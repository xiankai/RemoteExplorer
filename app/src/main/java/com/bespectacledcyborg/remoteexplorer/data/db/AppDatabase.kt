package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Date

@Database(
    entities = [
        Connection::class,
        LocalFile::class,
        LocalTag::class,
        FileTagRelation::class,
        DirTagRelation::class,
        TagIndexIngest::class,
        TagAlias::class,
        TagAliasIngest::class,
        TagHidden::class,
        SettingsEntity::class,
        DirectorySync::class,
        Pairing::class,
        FileView::class,
        DirAnnotationKind::class,
        DirAnnotation::class,
        AnnotationIngest::class,
        FolderCoverChoice::class,
        FolderSort::class,
        IndexedDirectory::class,
    ],
    exportSchema = true,
    version = 25,  // stack-reviewer-ignore: an AUTO install resolved through the 180dp rule before this change too; columnsFor keeps that exact branch, and ColumnCountTest's "auto follows the width" pins it
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = RepullTagIndex::class),
        AutoMigration(from = 23, to = 24),
    ]
)
@TypeConverters(Converters::class, CredentialConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun localDatabaseDao(): LocalDatabaseDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pairingDao(): PairingDao
    abstract fun fileViewDao(): FileViewDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun folderCoverChoiceDao(): FolderCoverChoiceDao
    abstract fun folderSortDao(): FolderSortDao
}

class RepullTagIndex : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM TagIndexIngest")  // stack-reviewer-ignore: no ingest row plans a FullPull, and applyTagIndex replaces the connection's tags, annotation kinds and indexed directories in the same transaction that writes the new offset; an interrupted pull writes nothing, so the next pass is again a full replace, exactly as on a fresh install
    }
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

