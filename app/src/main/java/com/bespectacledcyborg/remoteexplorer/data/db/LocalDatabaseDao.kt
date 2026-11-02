package com.bespectacledcyborg.remoteexplorer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.bespectacledcyborg.remoteexplorer.utils.Utils
import com.bespectacledcyborg.remoteexplorer.utils.Utils.BROWSEABLE_TYPES
import java.nio.file.Paths
import java.util.Date

/**
 * [tag] and every tag an alias group puts beside it, as a subquery binding `:tag`.
 *
 * One hop and no further: members of any group [tag] is in, never members of groups those
 * members are in. The generator refuses to chain merges because one generic translation
 * would drag unrelated concepts together, and walking the graph here would undo that.
 *
 * Across connections, like the tags themselves — a tag is a string, not a share's property.
 */
private const val TAG_AND_ALIASES =
    "SELECT :tag UNION " +
            "SELECT member.tag FROM TagAlias AS member " +
            "INNER JOIN TagAlias AS self ON member.canonical = self.canonical " +
            "WHERE self.tag = :tag"

@Dao
interface LocalDatabaseDao {
    /**
     * Files carrying [tag] or one of its aliases directly, which means the ones a file-scoped
     * index record named.
     *
     * Deliberately not extended to the contents of a tagged gallery. A directory-scoped tag
     * belongs to the gallery, and [getDirectoriesWithTag] is what surfaces it: expanding it
     * to files here would show a gallery's contents when they happen to be cached and the
     * gallery itself when they are not, so the same tag would list different kinds of thing
     * depending on where the user had been.
     *
     * DISTINCT because a file can carry two members of one group, and is still one file.
     */
    @Query(
        "SELECT DISTINCT LocalFile.* FROM LocalFile " +
                "INNER JOIN FileTagRelation " +
                "ON LocalFile.connectionId = FileTagRelation.connectionId " +
                "AND LocalFile.parentPath = FileTagRelation.parentPath " +
                "AND LocalFile.filename = FileTagRelation.filename " +
                "WHERE FileTagRelation.tag IN ($TAG_AND_ALIASES)"
    )
    fun getFilesWithTag(tag: String): List<LocalFile>

    /**
     * Directories carrying [tag] or one of its aliases, whether or not anyone has browsed
     * into them.
     *
     * The reason a tag listing shows anything at all for a gallery nobody has opened: the
     * listing cache only knows directories that have been visited, so on a fresh install it
     * has nothing to say about the entire index.
     *
     * One row per gallery. A pixiv work tagged both `眼鏡` and `丸眼鏡` matches twice through
     * the same group, and listing it twice would be the alias layer showing through. Which
     * of the matching tags the row carries is arbitrary, and nothing reads it.
     */
    @Query(
        // stack-reviewer-ignore: the returned tag is deliberately arbitrary; the only caller (FileRepository.getFilesWithTag via DirTagRelation.asFolder) reads connectionId and parentPath and never the tag, as the KDoc above says.
        "SELECT connectionId, parentPath, MIN(tag) AS tag FROM DirTagRelation " +
                "WHERE tag IN ($TAG_AND_ALIASES) " +
                "GROUP BY connectionId, parentPath ORDER BY parentPath"
    )
    fun getDirectoriesWithTag(tag: String): List<DirTagRelation>

    @Query("SELECT tag FROM DirTagRelation WHERE connectionId = :connectionId AND parentPath = :path")
    fun getDirectoryTags(connectionId: Int, path: String): List<String>

    @Query(
        "SELECT own.tag AS tag, 0 AS count, " +
                "(SELECT MIN(canonical) FROM TagAlias WHERE TagAlias.tag = own.tag) AS canonical " +
                "FROM ( " +
                "  SELECT tag FROM DirTagRelation " +
                "  WHERE connectionId = :connectionId AND parentPath = :directory " +
                "  UNION " +
                "  SELECT tag FROM FileTagRelation " +
                "  WHERE connectionId = :connectionId AND parentPath = :directory AND filename = :filename " +
                ") AS own ORDER BY own.tag"
    )
    fun getTagsOn(connectionId: Int, directory: String, filename: String?): List<LocalTagWithMetadata>

    @Query("SELECT canonical FROM TagAlias WHERE connectionId = :connectionId AND tag IN (:tags)")
    fun getAliasCanonicals(connectionId: Int, tags: List<String>): List<String>

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

    @Query(
        "SELECT * FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath " +
            "AND type = :type AND thumbnailStatus NOT IN (:excluded)"
    )
    fun getCoverCandidates(
        connectionId: Int,
        parentPath: String,
        type: FileType = FileType.FILE,
        excluded: List<ThumbnailStatus> = listOf(ThumbnailStatus.UNSUPPORTED, ThumbnailStatus.ERROR),
    ): List<LocalFile>

    @Query("SELECT filename FROM LocalFile WHERE connectionId = :connectionId AND parentPath = :parentPath AND thumbnailStatus = :thumbnailStatus LIMIT :limit")
    fun getProcessedFolderThumbnails(
        connectionId: Int,
        parentPath: String,
        thumbnailStatus: ThumbnailStatus = ThumbnailStatus.SUCCESS,
        limit: Int = 4
    ): List<String>

    /**
     * One entry per alias group, and one per tag in no group, with how many entries opening
     * it will show.
     *
     * The number next to something you can open should describe what is inside it, so this
     * counts listing rows — tagged files plus tagged galleries — rather than the files those
     * galleries contain. Counting files would put 5,000 next to a tag that opens onto 186
     * folders, and no amount of explaining makes that read as anything but wrong.
     *
     * A group is counted the way opening it lists: every gallery and every file carrying any
     * of its members, each once. So distinct across the whole group rather than a sum of
     * per-member counts — a gallery tagged both `眼鏡` and `丸眼鏡` is one gallery. The two
     * relations are counted separately and added rather than joined, since one query joining
     * both yields the product of the two matches.
     *
     * `tag` is one member actually present, which is what opening, thumbnails and search go
     * through: any member widens to the whole group ([TAG_AND_ALIASES]). `canonical` is the
     * group's key, null for a tag in no group. A raw tag that happens to spell some group's
     * canonical is a tag in no group, not a member.
     *
     * Two halves because only a group needs the distinct count. A tag in no group is counted
     * per tag as it always was, which is most of the list and cheap on the tag indices; the
     * DISTINCT work is spent on the groups alone. One pass keying every tag to its group and
     * counting distinct over all ~22k gallery rows cost 55 ms on the live library against
     * 15 ms for this, same result.
     *
     * Neither side touches [LocalFile], which is what lets a tag count a gallery nobody has
     * browsed into — the under-reporting this index exists to remove.
     *
     * A tag someone hid in the tag editor ([TagHidden]) is left out of both halves. Only the
     * list: opening a gallery still shows it, since hiding says the tag is useless to browse
     * by, not that it is wrong.
     */
    @Query(
        "SELECT LocalTag.tag AS tag, NULL AS canonical, " +
                "( " +
                "  SELECT COUNT(*) FROM FileTagRelation " +
                "  WHERE FileTagRelation.tag = LocalTag.tag " +
                ") + ( " +
                "  SELECT COUNT(*) FROM DirTagRelation " +
                "  WHERE DirTagRelation.tag = LocalTag.tag " +
                ") AS count " +
                "FROM LocalTag WHERE LocalTag.tag NOT IN (SELECT tag FROM TagAlias) " +
                "AND LocalTag.tag NOT IN (SELECT tag FROM TagHidden) " +
                "UNION ALL " +
                "SELECT MIN(LocalTag.tag) AS tag, g.canonical AS canonical, " +
                "( " +
                "  SELECT COUNT(*) FROM ( " +
                "    SELECT DISTINCT d.connectionId, d.parentPath FROM DirTagRelation AS d " +
                "    WHERE d.tag IN (SELECT m.tag FROM TagAlias AS m WHERE m.canonical = g.canonical) " +
                "  ) " +
                ") + ( " +
                "  SELECT COUNT(*) FROM ( " +
                "    SELECT DISTINCT f.connectionId, f.parentPath, f.filename FROM FileTagRelation AS f " +
                "    WHERE f.tag IN (SELECT m.tag FROM TagAlias AS m WHERE m.canonical = g.canonical) " +
                "  ) " +
                ") AS count " +
                "FROM (SELECT tag, MIN(canonical) AS canonical FROM TagAlias GROUP BY tag) AS g " +
                "INNER JOIN LocalTag ON LocalTag.tag = g.tag " +
                "WHERE LocalTag.tag NOT IN (SELECT tag FROM TagHidden) " +
                "GROUP BY g.canonical"
    )
    fun getTags(): List<LocalTagWithMetadata>

    @Insert(entity = LocalTag::class, onConflict = OnConflictStrategy.IGNORE)
    fun addTag(tag: LocalTag)

    @Insert(entity = FileTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addFileTagRelation(relation: FileTagRelation)


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
    // of every file in the directory, and the app re-generated every thumbnail for a listing that had usually not changed at all. Upsert also has
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
        // stack-reviewer-ignore: reaching here at all means SMB answered successfully — smbj throws on a failed or missing directory (STATUS_OBJECT_NAME_NOT_FOUND was a hard crash here until it was caught in ExplorerViewModel), so an empty listing is the server saying the directory is empty, not a transient blip. Guarding the delete on `remote.isEmpty()` would trade a speculative failure mode for a certain one: a directory genuinely emptied on the share would keep its stale rows forever, which is the exact staleness this reconcile exists to remove. The listing rows re-insert on the next listing with their thumbnails UNPROCESSED, so those regenerate; a deleted file's file-scoped index tags return only on the index's next full pull, which is right for a file the share really dropped.
        (cached.keys - listed).chunked(DELETE_CHUNK).forEach { removed ->
            deleteFileTagRelations(connectionId, parentPath, removed)
            deleteFiles(connectionId, parentPath, removed)
        }

        upsertDirectorySync(DirectorySync(connectionId, parentPath, syncedAt))
    }

    // ---- the rolled-up tag index ------------------------------------------------------

    @Query("SELECT * FROM TagIndexIngest WHERE connectionId = :connectionId")
    fun getTagIndexIngest(connectionId: Int): TagIndexIngest?

    @Upsert
    fun upsertTagIndexIngest(ingest: TagIndexIngest)

    @Insert(entity = DirTagRelation::class, onConflict = OnConflictStrategy.IGNORE)
    fun addDirTagRelation(relation: DirTagRelation)

    @Query("DELETE FROM DirTagRelation WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun deleteDirTagRelations(connectionId: Int, parentPath: String)

    @Query("DELETE FROM DirTagRelation WHERE connectionId = :connectionId")
    fun clearIngestedTags(connectionId: Int)

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
        deleted: Boolean,
    ) {
        deleteDirTagRelations(connectionId, parentPath)
        if (deleted) return

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

    @Transaction
    fun applyTagIndex(
        connectionId: Int,
        replace: Boolean,
        directories: List<DirectoryTagUpdate>,
        files: List<FileTagUpdate>,
        generation: String,
        bytesRead: Long,
    ) {
        if (replace) {
            clearIngestedTags(connectionId)
            clearAnnotationKinds(connectionId)
        }

        directories.forEach {
            applyDirectoryTags(connectionId, it.parentPath, it.tags, it.deleted)
            applyAnnotationKinds(connectionId, it.parentPath, if (it.deleted) emptyList() else it.annotations)
        }
        files.forEach {
            applyFileTags(connectionId, it.parentPath, it.filename, it.tags, it.deleted)
        }

        upsertTagIndexIngest(TagIndexIngest(connectionId, generation, bytesRead))
    }

    @Query("DELETE FROM DirAnnotationKind WHERE connectionId = :connectionId")
    fun clearAnnotationKinds(connectionId: Int)

    @Query("DELETE FROM DirAnnotationKind WHERE connectionId = :connectionId AND parentPath = :parentPath")
    fun deleteAnnotationKinds(connectionId: Int, parentPath: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addAnnotationKinds(kinds: List<DirAnnotationKind>)

    fun applyAnnotationKinds(connectionId: Int, parentPath: String, kinds: List<String>) {
        deleteAnnotationKinds(connectionId, parentPath)
        addAnnotationKinds(kinds.map { DirAnnotationKind(connectionId, parentPath, it) })
    }

    @Query("SELECT connectionId, parentPath FROM DirAnnotationKind WHERE kind = :kind")
    fun getAnnotatedDirectories(kind: String): List<AnnotatedDirectory>

    // ---- the alias layer ----------------------------------------------------------------

    @Query("SELECT generation FROM TagAliasIngest WHERE connectionId = :connectionId")
    fun getTagAliasGeneration(connectionId: Int): String?

    @Upsert
    fun upsertTagAliasIngest(ingest: TagAliasIngest)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addTagAliases(aliases: List<TagAlias>)

    @Query("DELETE FROM TagAlias WHERE connectionId = :connectionId")
    fun clearTagAliases(connectionId: Int)

    /**
     * Every tag beside every other member of its group, for searching the tag list.
     *
     * The same one hop as [TAG_AND_ALIASES], so a search finds exactly the tags whose
     * listing would contain what was typed — never a tag two groups away.
     * Across connections for the same reason: the tag list is, and so is the widening.
     */
    @Query(
        // stack-reviewer-ignore: across connections on purpose, like the tag list and TAG_AND_ALIASES; scoping search alone would make it disagree with what opening a tag lists
        "SELECT DISTINCT self.tag AS tag, member.tag AS alias FROM TagAlias AS self " +
                "INNER JOIN TagAlias AS member ON member.canonical = self.canonical " +
                "WHERE member.tag != self.tag"
    )
    fun getTagAliasPairs(): List<TagAliasPair>

    /**
     * Swaps one connection's aliases for a new generation, all at once.
     *
     * Replace rather than merge: the file is rewritten whole on every build, so a group
     * missing from the new one is a mapping someone corrected, and keeping it would leave the
     * wrong answer in place forever. One transaction, so a lookup never sees a connection
     * with half its groups, and the generation lands with the rows it describes.
     */
    @Transaction
    fun replaceTagAliases(
        connectionId: Int,
        aliases: List<TagAlias>,
        generation: String,
        hidden: List<String> = emptyList(),
    ) {
        clearTagAliases(connectionId)
        addTagAliases(aliases)
        clearTagHidden(connectionId)
        addTagHidden(hidden.map { TagHidden(connectionId, it) })
        upsertTagAliasIngest(TagAliasIngest(connectionId, generation))
    }

    @Query("DELETE FROM TagHidden WHERE connectionId = :connectionId")
    fun clearTagHidden(connectionId: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addTagHidden(hidden: List<TagHidden>)
}

data class DirectoryTagUpdate(
    val parentPath: String,
    val tags: List<String>,
    val deleted: Boolean,
    val annotations: List<String> = emptyList(),
)

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

    /**
     * Whether this opens in the app's own image viewer.
     *
     * This compared the row's [FileType] against a list of [IconType]s — two different
     * enums, which the `contains<Enum<*>>` made compile — so it was false for everything,
     * and every image was handed to whichever other app claimed `image/jpeg` over a
     * loopback URL. The viewer in ImageBrowserScreen was never reached (#67).
     */
    fun isBrowsable(): Boolean =
        type == FileType.FILE && Utils.guessIconType(filename) in BROWSEABLE_TYPES
}

@Entity
data class LocalTag(
    @PrimaryKey val tag: String,
)

@Entity
data class LocalTagWithMetadata(
    val tag: String,
    val count: Int,
    /** The alias group [tag] stands for, or null when it is in none. */
    val canonical: String? = null,
) {
    /**
     * What the list shows. A group's canonical names the concept (`clothes/pantyhose`), where
     * [tag] is only whichever member sorted first. A canonical still waiting for a root is
     * spelled `?/value`; the `?/` is the editor's marker, not part of the name.
     */
    val label: String get() = canonical?.removePrefix("?/") ?: tag
}

/** [tag] and one other tag in its alias group. */
data class TagAliasPair(
    val tag: String,
    val alias: String,
)

/**
 * A tag on one file, from a file-scoped index record.
 *
 * Indexed by tag because every read of it is by tag, and the primary key leads with the
 * path. Without the index the tag list scanned both relations once per tag — four seconds
 * for the real library's 3,470 tags, measured on the JVM, before a phone is involved.
 */
@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "filename",
        "tag",
    ],
    indices = [Index("tag")],
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
 *
 * Indexed by tag for the same reason as [FileTagRelation].
 */
@Entity(
    primaryKeys = [
        "connectionId",
        "parentPath",
        "tag",
    ],
    indices = [Index("tag")],
)
data class DirTagRelation(
    val connectionId: Int,
    val parentPath: String,
    val tag: String,
) {
    /**
     * The gallery as a listing row, so a tag can show one the app has never browsed into.
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
 * One raw tag's membership of an alias group, from the share's `aliases.ndjson`.
 *
 * Keyed by all three columns rather than by tag: the generator puts each tag in at most one
 * group today, but that is its invariant rather than this table's, and a tag that ever did
 * land in two should widen to both rather than lose one to a primary-key collision.
 *
 * Indexed by both columns, since a lookup goes tag → group → members.
 */
@Entity(
    primaryKeys = ["connectionId", "tag", "canonical"],
    indices = [Index("tag"), Index("canonical")],
)
data class TagAlias(
    val connectionId: Int,
    val tag: String,
    val canonical: String,
)

/**
 * A tag hidden in the tag editor, kept out of the tag list. Arrives with the aliases
 * (`hidden` in `aliases.meta.json`, groups already expanded to their members) and is
 * replaced with them, so a tag unhidden upstream comes back on the next alias pass.
 */
@Entity(
    primaryKeys = ["connectionId", "tag"],
    indices = [Index("tag")],
)
data class TagHidden(
    val connectionId: Int,
    val tag: String,
)

/**
 * Which alias generation a connection holds.
 *
 * Separate from [TagIndexIngest] because the two move independently: the index rebuilds
 * daily and is read by offset, the aliases change with every editor decision and are only
 * ever taken whole.
 */
@Entity(primaryKeys = ["connectionId"])
data class TagAliasIngest(
    val connectionId: Int,
    val generation: String,
)

@Entity(primaryKeys = ["connectionId", "parentPath", "kind"])
data class DirAnnotationKind(
    val connectionId: Int,
    val parentPath: String,
    val kind: String,
)

data class AnnotatedDirectory(
    val connectionId: Int,
    val parentPath: String,
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