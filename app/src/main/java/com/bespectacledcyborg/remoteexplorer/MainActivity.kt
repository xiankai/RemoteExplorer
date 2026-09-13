package com.bespectacledcyborg.remoteexplorer

import ConnectionDataSource
import LocalDatabaseDataSource
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.bespectacledcyborg.remoteexplorer.data.SambaFileDataSource
import com.bespectacledcyborg.remoteexplorer.data.ServerRepository
import com.bespectacledcyborg.remoteexplorer.data.SettingsRepository
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.db.SettingsEntity
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.data.tasks.ThumbnailGenerationWorker
import com.bespectacledcyborg.remoteexplorer.ui.components.MainView
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.SettingsViewModel
import com.github.panpf.sketch.SingletonSketch
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.DiskCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CustomWorkerFactory(
    private val tagRepository: TagRepository,
    private val fileRepository: FileRepository
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
                ThumbnailGenerationWorker(appContext, workerParameters, fileRepository)

            else -> null
        }
    }
}

class ExplorerViewModelFactory(
    private val application: Application,
    private val db: AppDatabase,
    private val defaultDispatcher: CoroutineDispatcher
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val sambaFileDataSource = SambaFileDataSource()
        val localDatabaseDataSource = LocalDatabaseDataSource(
            db.localDatabaseDao(),
            defaultDispatcher
        )
        val fileRepository = FileRepository(
            sambaFileDataSource,
            localDatabaseDataSource,
            defaultDispatcher
        )
        val tagRepository = TagRepository(
            fileRepository,
            localDatabaseDataSource
        )
        val connectionDataSource = ConnectionDataSource(
            db.connectionDao()
        )
        val connectionRepository = ConnectionRepository(
            connectionDataSource,
        )

        if (!WorkManager.isInitialized()) {
            // Injecting the needed repositories
            val workerFactory = CustomWorkerFactory(tagRepository, fileRepository)
            val workManagerConfig = Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMaxSchedulerLimit(50) // To avoid overloading the samba server
                .build()
            WorkManager.initialize(application.applicationContext, workManagerConfig)
        }

        // ThumbnailManager needs WorkManager up and running
        val thumbnailManager = ThumbnailManager(application.applicationContext)
        val serverRepository = ServerRepository(
            sambaFileDataSource,
            defaultDispatcher,
            thumbnailManager
        )

        @Suppress("UNCHECKED_CAST")
        return ExplorerViewModel(
            application,
            fileRepository,
            serverRepository,
            connectionRepository,
            tagRepository,
            SettingsRepository(db.settingsDao()),
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

        val db = Room.databaseBuilder(
            application.applicationContext,
            AppDatabase::class.java, "remote-explorer"
        )
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

        val explorerViewModel: ExplorerViewModel by viewModels {
            ExplorerViewModelFactory(application, db, Dispatchers.IO)
        }

        val settingsViewModel: SettingsViewModel by viewModels {
            SettingsViewModelFactory(application, db)
        }

        CoroutineScope(Dispatchers.IO).launch {
            val settingsDao = db.settingsDao()
            val settings = settingsDao.getSettingsOnce()
                ?: SettingsEntity().also { settingsDao.insert(it) } // add default row

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
            MainView(
                explorerViewModel = explorerViewModel,
                settingsViewModel = settingsViewModel
            )
        }
    }
}