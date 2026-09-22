package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.bespectacledcyborg.remoteexplorer.utils.Utils.BROWSEABLE_TYPES
import java.nio.file.Paths
import java.util.Date

@Dao
interface LocalDatabaseDao {
    /**
     * Files carrying [tag], from either kind of relation.
     *
     * Two sources with different shapes: a sidecar tags one file, the rolled-up index tags a
     * whole gallery. UNION rather than UNION ALL, because a file can legitimately be reached
     * both ways — a gallery tagged `artist:foo` whose own sidecar also says `artist:foo`
     * would otherwise appear twice in the grid.
     */
    @Query(
        "SELECT LocalFile.* FROM LocalFile " +
                "INNER JOIN FileTagRelation " +
                "ON LocalFile.connectionId = FileTagRelation.connectionId " +
                "AND LocalFile.parentPath = FileTagRelation.parentPath " +
                "AND LocalFile.filename = FileTagRelation.filename " +
                "WHERE FileTagRelation.tag = :tag " +
                "UNION " +
                "SELECT LocalFile.* FROM LocalFile " +
                "INNER JOIN DirTagRelation " +
                "ON LocalFile.connectionId = DirTagRelation.connectionId " +
                "AND LocalFile.parentPath = DirTagRelation.parentPath " +
                "WHERE DirTagRelation.tag = :tag"
    )
    fun getFilesWithTag(tag: String): List<LocalFile>

    /**
     * Directories carrying [tag], whether or not anyone has browsed into them.
     *
     * The reason a tag listing can show anything at all for a gallery nobody has opened.
     * [getFilesWithTag] can only return files the listing cache knows about, so on a fresh
     * install it answers nothing for the whole index — which is the under-reporting the
     * index exists to remove, reappearing one level up.
     */
    @Query(
        "SELECT TaggedDirectory.* FROM TaggedDirectory " +
                "INNER JOIN DirTagRelation " +
                "ON TaggedDirectory.connectionId = DirTagRelation.connectionId " +
                "AND TaggedDirectory.parentPath = DirTagRelation.parentPath " +
                "WHERE DirTagRelation.tag = :tag " +
                "ORDER BY TaggedDirectory.parentPath"
    )
    fun getDirectoriesWithTag(tag: String): List<TaggedDirectory>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getFiles(connectionId: Int, parentPath: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND type = :type")
    fun getFolders(
        connectionId: Int,
        parentPath: String,
        type: FileType = FileType.FOLDER
    ): List<LocalFile>

    // '%' || :filter || '%' rather than a bare LIKE :filter, which matched only a filename
    // equal to the search term — i.e. searching for anything short of the exact name found
    // nothing. LIKE is already case-insensitive for ASCII in SQLite.
    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename LIKE '%' || :filter || '%'")
    fun getFilesWithFilter(connectionId: Int, parentPath: String, filter: String): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath LIMIT :limit")
    fun getFolderThumbnails(connectionId: Int, parentPath: String, limit: Int = 4): List<LocalFile>

    @Query("SELECT filename FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND thumbnailStatus = :thumbnailStatus LIMIT :limit")
    fun getProcessedFolderThumbnails(
        connectionId: Int,
        parentPath: String,
        thumbnailStatus: ThumbnailStatus = ThumbnailStatus.SUCCESS,
        limit: Int = 4
    ): List<String>

    /**
     * Every tag with how many files carry it.
     *
     * The two relations are counted separately and added, rather than joined together: a
     * single query joining both produces the product of the two matches per tag, so a tag
     * appearing on three files inside a gallery already tagged with it would report nine.
     *
     * The directory side adds up stored file counts rather than counting rows, because
     * there are no per-file rows to count — and because counting [LocalFile] instead would
     * report a gallery as holding nothing until someone had browsed into it, which is the
     * under-reporting this whole index exists to remove.
     */
    @Query(
        "SELECT LocalTag.tag AS tag, " +
                "COALESCE(( " +
                "  SELECT COUNT(*) FROM FileTagRelation " +
                "  WHERE FileTagRelation.tag = LocalTag.tag " +
                "), 0) + COALESCE(( " +
                "  SELECT SUM(TaggedDirectory.fileCount) FROM DirTagRelation " +
                "  INNER JOIN TaggedDirectory " +
                "    ON TaggedDirectory.connectionId = DirTagRelation.connectionId " +
                "   AND TaggedDirectory.parentPath = DirTagRelation.parentPath " +
                "  WHERE DirTagRelation.tag = LocalTag.tag " +
                "), 0) AS count " +
                "FROM LocalTag"
    )
    fun getTags(): List<LocalTagWithMetadata>

    @Insert(entity = LocalTag::class, onConflict = OnConflictStrategy.IGNORE)
    fun addTag(tag: LocalTag)

    @Insert(entity = FileTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addFileTagRelation(relation: FileTagRelation)

    @Query("UPDATE LocalFile SET tagStatus = :status WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun updateTagStatus(connectionId: Int, parentPath: String, filename: String, status: TagStatus)

    @Query("UPDATE LocalFile SET thumbnailStatus = :status WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun updateThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
        status: ThumbnailStatus
    )

    @Query("SELECT thumbnailStatus FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename")
    fun getThumbnailStatus(
        connectionId: Int,
        parentPath: String,
        filename: String,
    ): ThumbnailStatus?

    /**
     * Puts a directory's failed thumbnails back in the queue.
     *
     * The reconcile only resets status for files that are new or whose `lastModified`
     * moved, which is what makes a recorded failure stick rather than being re-attempted on
     * every scroll. That is the desired behaviour for a file the decoders cannot read — but
     * it also means a file stays marked failed after the app itself gains a decoder that
     * could now handle it, which has just happened once. Pull-to-refresh is the escape
     * hatch, and the only one the user has.
     */
    @Query(
        "UPDATE LocalFile SET thumbnailStatus = 'UNPROCESSED' " +
                "WHERE connectionId = :connectionId AND parentPath = :parentPath " +
                "AND thumbnailStatus = 'ERROR'"
    )
    fun clearFailedThumbnails(connectionId: Int, parentPath: String)  // stack-reviewer-ignore: never reached on the main thread. Its only caller is syncDirectory, which sits inside listFiles' flow builder, and that flow ends `.flowOn(dispatcher)` (FileRepository.kt:176); the collector in ExplorerViewModel additionally launches on Dispatchers.IO. Non-suspend and unwrapped deliberately, matching reconcileDirectory two lines further down the same function — and if main-thread writes were reachable here, that call and the SMB listFiles beside it would have been throwing since long before this change.

    // --- Directory reconciliation -------------------------------------------------------
    //
    // A refresh used to be a single @Upsert of the whole remote listing, which wrote every
    // column — including tagStatus and thumbnailStatus, rebuilt as UNPROCESSED by
    // FileRepository.updateRemoteMapping. So one pull-to-refresh discarded the derived state
    // of every file in the directory, and the app re-generated every thumbnail and re-read
    // every .xmp sidecar for a listing that had usually not changed at all. Upsert also has
    // no way to notice a deletion, so files removed on the server stayed in the cache
    // forever.
    //
    // reconcileDirectory replaces it: insert what is new, touch only the columns the server
    // owns on what already exists, and delete what the server no longer lists.

    @Query("SELECT filename, lastModified FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getFileStamps(connectionId: Int, parentPath: String): List<LocalFileStamp>

    /**
     * IGNORE rather than REPLACE: a row that already exists carries derived state this
     * listing knows nothing about, and is handled by [refreshChangedFile] instead.
     */
    @Insert(entity = LocalFile::class, onConflict = OnConflictStrategy.IGNORE)
    fun insertNewFiles(files: List<LocalFile>)

    /**
     * Applied only to a file whose [LocalFile.lastModified] actually moved, so the thumbnail
     * and the tags are re-derived when the bytes changed and left alone when they did not.
     */
    @Query(
        "UPDATE LocalFile SET type = :type, lastModified = :lastModified, " +
                "thumbnailStatus = :thumbnailStatus, tagStatus = :tagStatus " +
                "WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename = :filename"
    )
    fun refreshChangedFile(
        connectionId: Int,
        parentPath: String,
        filename: String,
        type: FileType,
        lastModified: Date,
        thumbnailStatus: ThumbnailStatus,
        tagStatus: TagStatus = TagStatus.UNPROCESSED,
    )

    @Query("DELETE FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename IN (:filenames)")
    fun deleteFiles(connectionId: Int, parentPath: String, filenames: List<String>)

    // Left behind, a relation still counts towards LocalTag's total in getTags(), which
    // joins on FileTagRelation rather than on LocalFile — so a deleted file would keep
    // inflating its tags' counts.
    @Query("DELETE FROM FileTagRelation WHERE connectionId = :connectionId AND parentPath = :parentPath AND filename IN (:filenames)")
    fun deleteFileTagRelations(connectionId: Int, parentPath: String, filenames: List<String>)

    @Query("SELECT * FROM DirectorySync WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun getDirectorySync(connectionId: Int, parentPath: String): DirectorySync?

    @Upsert(entity = DirectorySync::class)
    fun upsertDirectorySync(sync: DirectorySync)

    /**
     * Brings one directory's cached rows in line with [remote] and records the sync, all in
     * one transaction so a failure part-way cannot leave the cache claiming to be fresher
     * than it is.
     */
    @Transaction
    fun reconcileDirectory(
        connectionId: Int,
        parentPath: String,
        remote: List<LocalFile>,
        syncedAt: Date,
    ) {
        val cached = getFileStamps(connectionId, parentPath).associateBy { it.filename }
        val (known, added) = remote.partition { cached.containsKey(it.filename) }

        insertNewFiles(added)

        known.forEach { file ->
            if (cached.getValue(file.filename).lastModified != file.lastModified) {
                refreshChangedFile(
                    connectionId = connectionId,
                    parentPath = parentPath,
                    filename = file.filename,
                    type = file.type,
                    lastModified = file.lastModified,
                    thumbnailStatus = file.thumbnailStatus,
                )
            }
        }

        val listed = remote.mapTo(mutableSetOf()) { it.filename }
        // Chunked because the filenames become bind parameters, and SQLite caps how many a
        // single statement may carry. A directory can hold more files than that cap.
        //
        // An empty `remote` deletes the directory's cached rows, and that is intended.
        // stack-reviewer-ignore: reaching here at all means SMB answered successfully — smbj throws on a failed or missing directory (STATUS_OBJECT_NAME_NOT_FOUND was a hard crash here until it was caught in ExplorerViewModel), so an empty listing is the server saying the directory is empty, not a transient blip. Guarding the delete on `remote.isEmpty()` would trade a speculative failure mode for a certain one: a directory genuinely emptied on the share would keep its stale rows forever, which is the exact staleness this reconcile exists to remove. The loss is also recoverable — rows re-insert on the next listing and sidecars come back UNPROCESSED, so tags re-index.
        (cached.keys - listed).chunked(DELETE_CHUNK).forEach { removed ->
            deleteFileTagRelations(connectionId, parentPath, removed)
            deleteFiles(connectionId, parentPath, removed)
        }

        upsertDirectorySync(DirectorySync(connectionId, parentPath, syncedAt))
    }

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type AND tagStatus = :status LIMIT :limit")
    fun getUnprocessedTagFiles(
        connectionId: Int,
        limit: Int,
        type: FileType = FileType.TAG,
        status: TagStatus = TagStatus.UNPROCESSED
    ): List<LocalFile>

    @Query("SELECT * FROM LocalFile WHERE connectionId = :connectionId AND type = :type LIMIT :limit")
    fun getAllTagFiles(
        connectionId: Int,
        limit: Int,
        type: FileType = FileType.TAG
    ): List<LocalFile>

    /**
     * Records one sidecar's tags against the file it describes.
     *
     * Which file that is used to be decided here, by a `LIKE` query against the directory.
     * That belonged to the source rather than to the DAO — see
     * `com.bespectacledcyborg.remoteexplorer.data.tags.pairSidecarWithMedia`, which also
     * documents the two ways the query got it wrong. [correspondingFilename] is null when the
     * sidecar describes nothing in its directory, which marks it REMOVED.
     */
    @Transaction
    fun updateFileTags(
        connectionId: Int,
        tagFile: LocalFile,
        correspondingFilename: String?,
        tags: List<String>,
    ) {
        val parentPath = tagFile.parentPath

        if (correspondingFilename == null) {
            updateTagStatus(connectionId, parentPath, tagFile.filename, TagStatus.REMOVED)
            return
        }

        tags.forEach {
            addTag(LocalTag(it))
            addFileTagRelation(
                FileTagRelation(connectionId, parentPath, correspondingFilename, it)
            )
        }
        updateTagStatus(connectionId, parentPath, tagFile.filename, TagStatus.SUCCESS)
    }

    // ---- the rolled-up tag index ------------------------------------------------------

    @Query("SELECT * FROM TagIndexIngest WHERE connectionId = :connectionId")
    fun getTagIndexIngest(connectionId: Int): TagIndexIngest?

    @Upsert
    fun upsertTagIndexIngest(ingest: TagIndexIngest)

    @Upsert
    fun upsertTaggedDirectory(directory: TaggedDirectory)

    @Insert(entity = DirTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addDirTagRelation(relation: DirTagRelation)

    @Query("DELETE FROM DirTagRelation WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun deleteDirTagRelations(connectionId: Int, parentPath: String)

    @Query("DELETE FROM TaggedDirectory WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun deleteTaggedDirectory(connectionId: Int, parentPath: String)

    @Query("DELETE FROM DirTagRelation WHERE connectionId = :connectionId")
    fun deleteAllDirTagRelations(connectionId: Int)

    @Query("DELETE FROM TaggedDirectory WHERE connectionId = :connectionId")
    fun deleteAllTaggedDirectories(connectionId: Int)

    /**
     * Throws away everything one connection ingested from the index.
     *
     * For a full pull, which replaces rather than adds to: the log it is reading is a
     * different one from whatever produced these rows, so a path that has since lost its
     * tags would otherwise keep them forever. Sidecar-derived rows are untouched — they came
     * from files on the share and this knows nothing about them.
     */
    @Transaction
    fun clearIngestedTags(connectionId: Int) {
        deleteAllDirTagRelations(connectionId)
        deleteAllTaggedDirectories(connectionId)
    }

    /**
     * Applies one record, replacing whatever that path had before.
     *
     * The log is append-only and the last record for a path wins, so a re-appended gallery
     * arrives here as a whole new set of tags rather than a delta — which means the old ones
     * have to go first. Adding without deleting would accumulate every tag a gallery ever
     * had, and a tag removed upstream would never disappear.
     *
     * A tombstone is the same operation without the insert.
     */
    @Transaction
    fun applyDirectoryTags(
        connectionId: Int,
        parentPath: String,
        tags: List<String>,
        fileCount: Int,
        deleted: Boolean,
    ) {
        deleteDirTagRelations(connectionId, parentPath)

        if (deleted) {
            deleteTaggedDirectory(connectionId, parentPath)
            return
        }

        // Recorded even with no tags: the directory is still one the index knows about, and
        // its file count is what a tag added to it later will be counted by.
        upsertTaggedDirectory(TaggedDirectory(connectionId, parentPath, fileCount))
        tags.forEach {
            addTag(LocalTag(it))
            addDirTagRelation(DirTagRelation(connectionId, parentPath, it))
        }
    }

    /**
     * Applies a file-scoped record, which is the same idea against the other relation.
     *
     * Rare — the real index has none today, since the one site that nests has no usable
     * tags — but the format allows it and a record this cannot store would be dropped
     * silently, which is the failure this project has produced most often.
     */
    @Transaction
    fun applyFileTags(
        connectionId: Int,
        parentPath: String,
        filename: String,
        tags: List<String>,
        deleted: Boolean,
    ) {
        deleteFileTagRelations(connectionId, parentPath, listOf(filename))
        if (deleted) return

        tags.forEach {
            addTag(LocalTag(it))
            addFileTagRelation(FileTagRelation(connectionId, parentPath, filename, it))
        }
    }

    /**
     * One pass over the index, applied as a single transaction.
     *
     * All of it or none: a pass that stopped half way would leave the stored offset
     * disagreeing with the rows, and the next pass would resume from a point it never
     * actually reached. Since the offset is written here too, either both land or neither
     * does and the pass simply runs again.
     *
     * [replace] is set for a full pull, where the log being read is a different one from
     * whatever produced the existing rows — so a path that has since lost its tags has to
     * lose them here rather than keeping them forever.
     */
    @Transaction
    fun applyTagIndex(
        connectionId: Int,
        replace: Boolean,
        directories: List<DirectoryTagUpdate>,
        files: List<FileTagUpdate>,
        generation: String,
        bytesRead: Long,
    ) {
        if (replace) clearIngestedTags(connectionId)

        directories.forEach {
            applyDirectoryTags(connectionId, it.parentPath, it.tags, it.fileCount, it.deleted)
        }
        files.forEach {
            applyFileTags(connectionId, it.parentPath, it.filename, it.tags, it.deleted)
        }

        upsertTagIndexIngest(TagIndexIngest(connectionId, generation, bytesRead))
    }
}

/** One directory-scoped record, in the terms the database stores. */
data class DirectoryTagUpdate(
    val parentPath: String,
    val tags: List<String>,
    val fileCount: Int,
    val deleted: Boolean,
)

/** One file-scoped record, likewise. */
data class FileTagUpdate(
    val parentPath: String,
    val filename: String,
    val tags: List<String>,
    val deleted: Boolean,
)

@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "filename",
    ]
)
data class LocalFile(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val type: FileType,
    @Deprecated("Using Utils.canHaveThumbnail instead") val canHaveThumbnail: Boolean,
//    val thumbnailPath: String?,
    val tagStatus: TagStatus = TagStatus.UNPROCESSED,
    val thumbnailStatus: ThumbnailStatus = ThumbnailStatus.UNPROCESSED,
    val lastModified: Date,
) {
    fun fullPath(): String {
        return Paths.get(parentPath, filename).toString()
    }

    fun isBrowsable(): Boolean {
        return BROWSEABLE_TYPES.contains<Enum<*>>(type)
    }
}

@Entity
data class LocalTag(
    @PrimaryKey val tag: String,
)

@Entity
data class LocalTagWithMetadata(
    val tag: String,
    val count: Int,
)

@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "filename",
        "tag",
    ]
)
data class FileTagRelation(
    val connectionId: Int,
    val parentPath: String,
    val filename: String,
    val tag: String
)

/**
 * A tag on every file directly inside a directory.
 *
 * The shape the rolled-up index actually has: gallery-dl tags a gallery, not a file. Storing
 * it file-scoped would mean fanning each record out across its contents — about 3.0M rows
 * for the real library against roughly 17k for this, and every re-ingest would have to
 * rewrite the fan-out rather than three columns.
 *
 * "Directly inside" is exact, not a simplification. Every tagged gallery holds its files at
 * one level, which the generator now enforces rather than leaving to the current layout, so
 * this joins to [LocalFile] on `parentPath` equality. If that ever has to bend, the join
 * becomes a prefix range rather than a different table.
 */
@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "tag",
    ]
)
data class DirTagRelation(
    val connectionId: Int,
    val parentPath: String,
    val tag: String,
)

/**
 * A directory the index has tagged, and how many files it held when it was last tagged.
 *
 * The count is stored because a directory-scoped tag has no per-file rows to count, and the
 * tag list shows a number next to each tag. It comes from the index rather than from
 * [LocalFile], which only knows about directories that have been browsed — counting rows
 * there would report a tag as covering nothing until someone happened to visit its gallery.
 *
 * Accurate as of the last tag change rather than as of now: the generator re-emits when a
 * gallery's tags change or its directory's mtime moves, so a count can lag by a rebuild.
 * That is a number in a list being slightly stale, which nobody can see.
 */
@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
    ]
)
data class TaggedDirectory(
    val connectionId: Int,
    val parentPath: String,
    val fileCount: Int,
) {
    /**
     * The directory as a listing row, so a tag can show a gallery the app has never browsed
     * into.
     *
     * Not stored, because it is not a fact about the share — it is this row rendered the way
     * the grid understands. `lastModified` is the epoch for the same reason: the index knows
     * when a gallery was tagged, not when it was written, and inventing a plausible date
     * would be worse than an obviously absent one.
     */
    fun asFolder(): LocalFile {
        val path = Paths.get(parentPath)
        return LocalFile(
            connectionId = connectionId,
            parentPath = path.parent?.toString().orEmpty(),
            filename = path.fileName?.toString() ?: parentPath,
            type = FileType.FOLDER,
            canHaveThumbnail = false,
            lastModified = Date(0),
        )
    }
}

/**
 * How far one connection's ingest of the rolled-up index has got.
 *
 * [bytesRead] is a byte offset into the log rather than a record count, because that is what
 * makes the next pass a ranged read instead of a re-parse of everything. It only means
 * anything together with [generation]: the same offset in a rebuilt log points somewhere
 * arbitrary, which is why a generation change throws both away.
 */
@Entity(primaryKeys = ["connectionId"])
data class TagIndexIngest(
    val connectionId: Int,
    val generation: String,
    val bytesRead: Long,
)

/**
 * When a directory was last reconciled against the share.
 *
 * Its absence is the only thing that distinguishes "never fetched" from "fetched, and the
 * folder is genuinely empty" — a distinction the cache could not previously make, so an
 * empty folder re-hit SMB on every single visit.
 */
@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
    ]
)
data class DirectorySync(
    val connectionId: Int,
    val parentPath: String,
    val lastSyncedAt: Date,
)

/** Just enough of a cached row to decide insert / update / delete during a reconcile. */
data class LocalFileStamp(
    val filename: String,
    val lastModified: Date,
)

/**
 * How many filenames may go into one `IN (...)` clause. SQLite's default ceiling on bind
 * parameters is 999; staying well under it leaves room for the statement's other arguments.
 */
private const val DELETE_CHUNK = 900

enum class FileType {
    FILE, FOLDER, TAG, CONNECTION, ADD_CONNECTION
}

enum class TagStatus {
    UNPROCESSED, SUCCESS, ERROR, REMOVED
}

enum class ThumbnailStatus {
    UNPROCESSED, SUCCESS, ERROR, UNSUPPORTED  // stack-reviewer-ignore: dropping NESTED cannot orphan a stored row — `git log --all -p -S'NESTED'` shows its only non-declaration use in any commit is a COMPARISON inside the long-disabled thumbnailsJob, and the sole writers of thumbnailStatus (updateRemoteMapping, updateFileThumbnailStatus) pass only the four values above.
}