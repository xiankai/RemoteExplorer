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
import com.bespectacledcyborg.remoteexplorer.data.TagRepository
import com.bespectacledcyborg.remoteexplorer.data.ThumbnailManager
import com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase
import com.bespectacledcyborg.remoteexplorer.data.tasks.ScanTagsWorker
import com.bespectacledcyborg.remoteexplorer.data.tasks.ThumbnailGenerationWorker
import com.bespectacledcyborg.remoteexplorer.ui.components.MainView
import com.bespectacledcyborg.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.DiskCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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
    private val defaultDispatcher: CoroutineDispatcher
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = Room.databaseBuilder(
            application.applicationContext,
            AppDatabase::class.java, "remote-explorer"
        )
            .fallbackToDestructiveMigration()
//            .setQueryCallback(
//                { sqlQuery, bindArgs ->
//                    Log.d("RoomDatabase", "SQL Query: $sqlQuery SQL Args: $bindArgs")
//                },
//                Executors.newSingleThreadExecutor()
//            )
            .build()
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

        // ThumbnailRepository needs WorkManager up and running
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
        ) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val explorerViewModel: ExplorerViewModel by viewModels {
            ExplorerViewModelFactory(application, Dispatchers.IO)
        }

        Sketch.Builder(applicationContext).apply {
            downloadCacheOptions(
                DiskCache.Options(
                    maxSize = 4L * 1024 * 1024 * 1024
                )
            )
        }.build()

        setContent {
            MainView(explorerViewModel = explorerViewModel)
        }
    }
}