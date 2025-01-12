package com.example.remoteexplorer

import ConnectionDataSource
import LocalDatabaseDataSource
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.remoteexplorer.ui.theme.RemoteExplorerTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import com.example.remoteexplorer.data.ConnectionRepository
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.SambaFileDataSource
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.ThumbnailRepository
import com.example.remoteexplorer.data.db.AppDatabase
import com.example.remoteexplorer.data.db.ConnectionDao
import com.example.remoteexplorer.data.db.LocalDatabaseDao
import com.example.remoteexplorer.ui.screens.header.Header
import com.example.remoteexplorer.ui.components.MainView
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.utils.PropertiesLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class ExplorerViewModelFactory(
    private val application: Application,
    private val defaultDispatcher: CoroutineDispatcher
): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = Room.databaseBuilder(
            application.applicationContext,
            AppDatabase::class.java, "remote-explorer"
        ).fallbackToDestructiveMigration().build()
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
        val thumbnailRepository = ThumbnailRepository(
            application.applicationContext,
            sambaFileDataSource,
            defaultDispatcher
        )
        val connectionDataSource = ConnectionDataSource(
            db.connectionDao()
        )
        val connectionRepository = ConnectionRepository(
            connectionDataSource,
        )

        return ExplorerViewModel(application,
            fileRepository,
                    serverRepository,
                    thumbnailRepository,
                    connectionRepository,
                    defaultDispatcher,
            ) as T
    }
}

class MainActivity : ComponentActivity() {
    lateinit var database: AppDatabase
        private set
    lateinit var connectionDao: ConnectionDao
        private set
    lateinit var localDatabaseDao: LocalDatabaseDao
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // For local dev
        PropertiesLoader.loadProperties(this)

        SingletonImageLoader.setSafe {
            ImageLoader.Builder(this)
                .diskCache {
                    DiskCache.Builder()
                        .directory(this.cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.2)
                        .build()
                }
                .build()
        }

        val explorerViewModel: ExplorerViewModel by viewModels {
            ExplorerViewModelFactory(application, Dispatchers.IO)
        }

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "app_database"
        ).fallbackToDestructiveMigration().build()
        connectionDao = database.connectionDao()
        localDatabaseDao = database.localDatabaseDao()

        setContent {
            RemoteExplorerTheme {
                Scaffold(
                    topBar = { Header(explorerViewModel) },
                    content =  {
                        innerPadding ->
                            MainView(
                                modifier = Modifier.padding(innerPadding),
                                explorerViewModel = explorerViewModel,
                            )
                    },
                    containerColor = MaterialTheme.colorScheme.background
                )
            }
        }
    }
}