package com.example.remoteexplorer

import ConnectionDataSource
import LocalDatabaseDataSource
import ScanTagsWorker
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
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
import com.example.remoteexplorer.data.ConnectionRepository
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.SambaFileDataSource
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.TagRepository
import com.example.remoteexplorer.data.db.AppDatabase
import com.example.remoteexplorer.ui.components.MainView
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.utils.PropertiesLoader
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.cache.DiskCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors

class ScanTagsWorkerFactory(
    private val tagRepository: TagRepository
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            ScanTagsWorker::class.java.name ->
                ScanTagsWorker(appContext, workerParameters, tagRepository)
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
        val serverRepository = ServerRepository(
            sambaFileDataSource,
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

        // Injecting TagRepository into ScanTagsWorker
        val myWorkerFactory = ScanTagsWorkerFactory(tagRepository)
        val config = Configuration.Builder()
            .setWorkerFactory(myWorkerFactory)
            .build()
        WorkManager.initialize(application.applicationContext, config)

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

        // For local dev
        PropertiesLoader.loadProperties(this)

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