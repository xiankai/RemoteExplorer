package com.example.remoteexplorer

import ConnectionDataSource
import LocalDatabaseDataSource
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.example.remoteexplorer.data.ConnectionRepository
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.SambaFileDataSource
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.TagRepository
import com.example.remoteexplorer.data.db.AppDatabase
import com.example.remoteexplorer.data.db.ConnectionDao
import com.example.remoteexplorer.data.db.LocalDatabaseDao
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
        val tagRepository = TagRepository(
            fileRepository,
            localDatabaseDataSource,
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
            connectionRepository,
            tagRepository,
        ) as T
    }
}


class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var connectionDao: ConnectionDao
    private lateinit var localDatabaseDao: LocalDatabaseDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // For local dev
        PropertiesLoader.loadProperties(this)

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
            MainView(explorerViewModel = explorerViewModel)
        }
    }
}