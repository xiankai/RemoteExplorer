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
    // 12 adds the two tables the rolled-up tag index needs: DirTagRelation for tags that
    // belong to a gallery rather than a file, and TagIndexIngest for how far each connection
    // has read the log. Both new, neither touching an existing row, so it auto-migrates —
    // and an existing install comes out with no ingest state, which reads as "never pulled"
    // and starts from a full pull.
    //
    // 13 adds the alias layer: TagAlias for which raw tags name the same thing, and
    // TagAliasIngest for which generation of it each connection holds. New tables again, so
    // it auto-migrates, and no ingest state reads as "never pulled". It also indexes both tag
    // relations by tag, which only adds indices.
    //
    // 14 adds local roots (#51): Connection.type, defaulted to SMB so every existing row is
    // what it already was, and Connection.localRoot. The SMB columns become nullable, which
    // SQLite cannot ALTER — so the AutoMigration rebuilds the Connection table and copies
    // the rows across, stored password ciphertext included, byte for byte.
    //
    // 15 adds Pairing, which local folder is a copy of which remote one. A new table that
    // touches no existing row, so it auto-migrates, and an existing install has no pairings.
    //
    // 16 adds TagHidden, the tags hidden in the tag editor. A new table, so it auto-migrates,
    // and an existing install hides nothing until its next alias pass fills it.
    //
    // 17 adds settings.themeMode, defaulted to LIGHT so an existing install opens light.
    //
    // 18 adds FileView, the record of what has been opened and watched (#76), and the two
    // settings that say where a viewing day starts. A new table and two defaulted columns,
    // so it auto-migrates: nothing existing is touched, an existing install starts with
    // nothing viewed, and its days start at noon in Asia/Tokyo.
    //
    // 19 adds the author captions (infra#312): DirAnnotationKind from the index,
    // DirAnnotation and AnnotationIngest from the annotation store, and settings.showCaptions,
    // off. New tables and a defaulted column, so it auto-migrates, and an existing install
    // shows no captions until it turns them on.
    //
    // 20 adds FolderCoverChoice, the cover each folder was set to (#103), and
    // settings.coverShuffle, defaulted to MARKED. A new table and a defaulted column, so it
    // auto-migrates, and an existing install keeps every cover as it was.
    //
    // 21 adds FolderSort, a folder's own sort, and two settings: defaultSort, NAME_ASC, and
    // columnTileWidthDp, null. An existing install keeps its stored column count until the
    // next pick.
    //
    // 22 adds settings.showHiddenFiles, off (#128). A defaulted column, so it auto-migrates,
    // and an existing install starts hiding clutter.
    //
    // 23 adds IndexedDirectory, when each gallery arrived on the share (#157). A new table,
    // but the rows it needs are in records an install has already read, so the migration
    // drops TagIndexIngest and the next pass is a full pull that fills it.
    version = 23,  // stack-reviewer-ignore: an AUTO install resolved through the 180dp rule before this change too; columnsFor keeps that exact branch, and ColumnCountTest's "auto follows the width" pins it
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

