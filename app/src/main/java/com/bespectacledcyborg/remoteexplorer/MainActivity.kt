package com.bespectacledcyborg.remoteexplorer

import ConnectionDataSource
import LocalDatabaseDataSource
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.bespectacledcyborg.remoteexplorer.data.perf.LoadMetrics
import com.bespectacledcyborg.remoteexplorer.data.perf.SentryLoadMetrics
import com.bespectacledcyborg.remoteexplorer.ui.components.LocalLoadMetrics
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.bespectacledcyborg.remoteexplorer.data.ConnectionRepository
import com.bespectacledcyborg.remoteexplorer.data.FileRepository
import com.bespectacledcyborg.remoteexplorer.data.ConnectionOpener
import com.bespectacledcyborg.remoteexplorer.data.LocalFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.PairingRepository
import com.bespectacledcyborg.remoteexplorer.data.SourceResolver
import com.bespectacledcyborg.remoteexplorer.data.RoutingFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.ServerRepository
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.crypto.KeystoreCredentialCipher
import com.bespectacledcyborg.remoteexplorer.data.tags.AliasFileTagSource
import com.bespectacledcyborg.remoteexplorer.data.tags.AnnotationFileTagSource
import com.bespectacledcyborg.remoteexplorer.data.tags.IndexLog
import com.bespectacledcyborg.remoteexplorer.data.tags.IndexLogReader
import com.bespectacledcyborg.remoteexplorer.data.CaptionRepository
import com.bespectacledcyborg.remoteexplorer.data.tags.CombinedTagSource
import com.bespectacledcyborg.remoteexplorer.data.tags.IndexFileTagSource
import com.bespectacledcyborg.remoteexplorer.data.tags.SharesOnlyTagSource
import com.bespectacledcyborg.remoteexplorer.data.tags.SmbTagIndexFiles
import com.bespectacledcyborg.remoteexplorer.data.tags.TagIndexReader
import com.bespectacledcyborg.remoteexplorer.data.tags.XmpSidecarTagSource
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.FallbackThumbnailSource
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.LocalImageThumbnailSource
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.PairedThumbnailStatuses
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.LocalVideoThumbnailSource
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.PlatformVideoThumbnailSource
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailSources
import com.bespectacledcyborg.remoteexplorer.data.thumbnails.ThumbnailStatusStore
import com.bespectacledcyborg.remoteexplorer.ui.components.IconType
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.ConnectionType
import com.bespectacledcyborg.remoteexplorer.data.db.CredentialConverters
import com.bespectacledcyborg.remoteexplorer.data.db.EncryptSavedPasswords
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.db.ThumbnailStatus
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.data.tasks.ThumbnailGenerationWorker
import com.bespectacledcyborg.remoteexplorer.ui.components.MainView
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SettingsViewModel
import com.bespectacledcyborg.remoteexplorer.ui.theme.applyThemeMode
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.DiskCache
import com.github.panpf.sketch.fetch.supportKtorHttpUri
import com.github.panpf.sketch.http.KtorStack
import com.github.panpf.sketch.util.KtorHttpComponentProvider
import com.bespectacledcyborg.remoteexplorer.data.imageHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.bespectacledcyborg.remoteexplorer.data.views.MediaDuration
import com.bespectacledcyborg.remoteexplorer.data.views.PlaybackSessions
import com.bespectacledcyborg.remoteexplorer.data.views.ServedOpens
import com.bespectacledcyborg.remoteexplorer.data.views.ViewRecorder
import kotlinx.coroutines.launch

class CustomWorkerFactory(
    private val tagRepository: TagRepository,
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            ScanTagsWorker::class.java.name ->
                ScanTagsWorker(appContext, workerParameters, tagRepository)

            ThumbnailGenerationWorker::class.java.name ->
                ThumbnailGenerationWorker(
                    appContext, workerParameters, fileRepository, settingsRepository
                )

            else -> null
        }
    }
}

class ExplorerViewModelFactory(
    private val application: Application,
    private val db: AppDatabase,
    private val defaultDispatcher: CoroutineDispatcher,
    private val metrics: LoadMetrics,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val sambaFileDataSource = SambaFileDataSource()
        // The one instance everything that reads a file holds. Each connection is routed
        // to SMB or to the device's own storage by its type, when it connects.
        val fileDataSource = RoutingFileDataSource(
            smb = sambaFileDataSource,
            local = LocalFileDataSource(),
        )
        val localDatabaseDataSource = LocalDatabaseDataSource(
            db.localDatabaseDao(),
            defaultDispatcher
        )
        val fileRepository = FileRepository(
            fileDataSource,
            localDatabaseDataSource,
            defaultDispatcher,
            metrics = metrics,
            sourceKind = fileDataSource::sourceKind,
        )
        // Both sources for every connection, which is the union rather than a choice: the
        // rolled-up index covers the galleries gallery-dl downloaded, sidecars cover
        // whatever a photo tool wrote beside the files, and a share can have either, both
        // or neither. A share with no index reports nothing and costs one small read to
        // find that out. The alias layer rides alongside: it adds no tags, only which ones
        // mean the same thing, and has to run whether or not the index changed.
        //
        // Shares only: a local root takes its tags from the share it is paired with.
        val tagIndexFiles = SmbTagIndexFiles(sambaFileDataSource)
        val connectionDao = db.connectionDao()
        val tagRepository = TagRepository(
            localDatabaseDataSource,
            {
                SharesOnlyTagSource(
                    CombinedTagSource(
                        listOf(
                            IndexFileTagSource(
                                TagIndexReader(tagIndexFiles), localDatabaseDataSource
                            ),
                            AliasFileTagSource(tagIndexFiles, localDatabaseDataSource),
                            AnnotationFileTagSource(
                                IndexLogReader(tagIndexFiles, IndexLog.ANNOTATIONS),
                                db.annotationDao(),
                                defaultDispatcher,
                            ),
                            XmpSidecarTagSource(fileRepository, localDatabaseDataSource),
                        )
                    ),
                    isLocal = { id ->
                        connectionDao.getConnectionType(id) == ConnectionType.LOCAL
                    },
                )
            },
        )
        val connectionDataSource = ConnectionDataSource(
            connectionDao
        )
        val connectionRepository = ConnectionRepository(
            connectionDataSource,
        )
        val settingsRepository = SettingsRepository(db.settingsDao())

        if (!WorkManager.isInitialized()) {
            // Injecting the needed repositories
            val workerFactory =
                CustomWorkerFactory(tagRepository, fileRepository, settingsRepository)
            val workManagerConfig = Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMaxSchedulerLimit(50) // To avoid overloading the samba server
                .build()
            WorkManager.initialize(application.applicationContext, workManagerConfig)
        }

        // ThumbnailManager needs WorkManager up and running
        val thumbnailManager = ThumbnailManager(application.applicationContext) {
            settingsRepository.getSettingsOnce()?.thumbnailCacheVolume
        }

        // Resolved per media type, so video can be pointed elsewhere without moving images
        // with it. All of these are on-device today and none writes to the share.
        //
        // The two video decoders cover different formats, so the default is both in order
        // rather than a choice between them — see FallbackThumbnailSource. `?decoder=` still
        // selects one on its own, which is how they get measured separately (#41).
        val ffmpegVideoThumbnails = LocalVideoThumbnailSource(fileDataSource)
        val platformVideoThumbnails = PlatformVideoThumbnailSource(fileDataSource)
        val videoThumbnails = FallbackThumbnailSource(
            listOf(platformVideoThumbnails, ffmpegVideoThumbnails)
        )
        val imageThumbnails = LocalImageThumbnailSource(fileDataSource)
        val thumbnailSources = ThumbnailSources { type, variant ->
            when (type) {
                IconType.VIDEO -> when (variant) {
                    "platform" -> platformVideoThumbnails
                    "ffmpeg" -> ffmpegVideoThumbnails
                    else -> videoThumbnails
                }

                IconType.IMAGE -> imageThumbnails
                else -> null
            }
        }

        // Backed by the listing cache, which already carries a thumbnailStatus per file and
        // already resets it when a file changes on the share — so a recorded failure
        // expires on exactly the right event without a second store to keep in step.
        val listingStatuses = object : ThumbnailStatusStore {
            override suspend fun statusOf(connectionId: Int, path: String) =
                fileRepository.getFileThumbnailStatus(connectionId, path)

            override suspend fun record(
                connectionId: Int,
                path: String,
                status: ThumbnailStatus,
            ) = fileRepository.updateFileThumbnailStatus(connectionId, path, status)
        }

        // Which local folders are copies of which remote ones (#51). One snapshot, read by
        // the server to name and find a file and by the view model to build its URLs, so
        // both caches — ours and Sketch's — agree on what a file is called.
        val pairingRepository = PairingRepository(db.pairingDao())
        val thumbnailStatuses = PairedThumbnailStatuses(listingStatuses, pairingRepository::current)
        val connectionOpener = ConnectionOpener(
            files = fileDataSource,
            lookUp = connectionDao::getConnection,
            open = fileRepository::connect,
        )

        // What has been opened and watched (#76). The playback tracker outlives any one
        // screen, as the server does, so it gets a scope of its own rather than a view
        // model's.
        val viewRecorder = ViewRecorder(db.fileViewDao(), settingsRepository::getSettingsOnce)
        val mediaDuration = MediaDuration(fileDataSource)
        val playbackSessions = PlaybackSessions(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            durationOf = { served -> mediaDuration.of(served) },
            credit = viewRecorder::recordWatched,
        ).also { it.start() }

        val serverRepository = ServerRepository(
            fileDataSource,
            defaultDispatcher,
            thumbnailManager,
            thumbnailSources,
            thumbnailStatuses,
            canonicalize = { id, path -> pairingRepository.current().canonicalize(id, path) },
            resolver = SourceResolver(
                fileDataSource,
                pairingRepository::current,
                connectionOpener::ensureOpen,
            ),
            playback = playbackSessions,
            opens = ServedOpens(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                record = viewRecorder::recordOpen,
            ),
            metrics = metrics,
            sourceKind = fileDataSource::sourceKind,
        )

        @Suppress("UNCHECKED_CAST")
        return ExplorerViewModel(
            application,
            fileRepository,
            serverRepository,
            connectionRepository,
            tagRepository,
            settingsRepository,
            thumbnailManager,
            pairingRepository,
            viewRecorder,
            CaptionRepository(db.annotationDao(), db.localDatabaseDao(), defaultDispatcher),
            metrics,
            fileDataSource::sourceKind,
        ) as T
    }
}

class SettingsViewModelFactory(
    private val application: Application,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
//        val settingsRepository = SettingsRepository(dataStore)
        val settingsRepository = SettingsRepository(db.settingsDao())

        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(
            application,
            settingsRepository,
        ) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One cipher instance for both the converter and the migration, so a row written by
        // one is readable by the other.
        val credentialCipher = KeystoreCredentialCipher()

        val db = Room.databaseBuilder(
            application.applicationContext,
            AppDatabase::class.java, "remote-explorer"
        )
            .addTypeConverter(CredentialConverters(credentialCipher))
            .addMigrations(EncryptSavedPasswords(credentialCipher))
            // No destructive fallback. It was silently deleting the database on any schema
            // mismatch, and this database is not purely a cache: LocalFile rows can be
            // re-fetched from the share, but Connection rows are hand-entered hostnames and
            // credentials that nothing can reconstruct.
            //
            // The migration chain now covers 5 -> 6 -> 7 -> 8, so every reachable version
            // has a path. A version this code does not know about — a future gap, or a
            // downgrade to an older APK — now fails loudly instead of wiping the user's
            // saved connections. A loud failure is recoverable (install the newer build);
            // a silent wipe is not.
//            .setQueryCallback(
//                { sqlQuery, bindArgs ->
//                    Log.d("RoomDatabase", "SQL Query: $sqlQuery SQL Args: $bindArgs")
//                },
//                Executors.newSingleThreadExecutor()
//            )
            .build()

        val loadMetrics = SentryLoadMetrics()
        val explorerViewModel: ExplorerViewModel by viewModels {
            ExplorerViewModelFactory(application, db, Dispatchers.IO, loadMetrics)
        }

        val settingsViewModel: SettingsViewModel by viewModels {
            SettingsViewModelFactory(application, db)
        }

        CoroutineScope(Dispatchers.IO).launch {
            val settingsDao = db.settingsDao()
            val settings = settingsDao.getSettingsOnce()
                ?: SettingsEntity().also { settingsDao.insert(it) } // add default row

            applyThemeMode(applicationContext, settings.themeMode)

            // The configured Sketch has to be REGISTERED as the singleton. This previously
            // built an instance and dropped it on the floor, so every image load used
            // Sketch's own default and the "Max Storage Cache Size" setting did nothing at
            // all. It also read the value from the settings StateFlow synchronously during
            // onCreate — before the database had emitted — so it was always the initial
            // default rather than what was stored.
            //
            // setSafe registers a factory and is a no-op if a Sketch already exists, so this
            // losing a race against a very early image load degrades to the old behaviour
            // rather than crashing.
            SingletonSketch.setSafe { context ->
                Sketch.Builder(context).apply {
                    addIgnoredComponentProvider(KtorHttpComponentProvider::class)
                    addComponents { supportKtorHttpUri(KtorStack(imageHttpClient())) }
                    downloadCacheOptions(
                        DiskCache.Options(
                            // Convert setting to actual GB
                            maxSize = settings.maxStorageCacheSize * 1024L * 1024 * 1024
                        )
                    )
                }.build()
            }
        }

        setContent {
            CompositionLocalProvider(LocalLoadMetrics provides loadMetrics) {
                MainView(
                    explorerViewModel = explorerViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
        }
    }
}
