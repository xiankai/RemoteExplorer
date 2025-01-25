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
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import coil3.video.VideoFrameDecoder
import com.example.remoteexplorer.data.ConnectionRepository
import com.example.remoteexplorer.data.FileRepository
import com.example.remoteexplorer.data.SambaFileDataSource
import com.example.remoteexplorer.data.ServerRepository
import com.example.remoteexplorer.data.db.AppDatabase
import com.example.remoteexplorer.data.db.ConnectionDao
import com.example.remoteexplorer.data.db.LocalDatabaseDao
import com.example.remoteexplorer.ui.components.IconType
import com.example.remoteexplorer.ui.components.MainView
import com.example.remoteexplorer.ui.viewmodels.ExplorerViewModel
import com.example.remoteexplorer.utils.PropertiesLoader
import com.example.remoteexplorer.utils.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

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
            ) as T
    }
}

class RequestHeaderInterceptor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val filename = chain.request().url.pathSegments.last()
        val isVideoRequest = Utils.guessIconType(filename) == IconType.VIDEO
        val isThumbnailRequest = chain.request().url.queryParameter("thumbnail") != null
        if (isVideoRequest && isThumbnailRequest) {
            val request = chain.request().newBuilder()
                .addHeader("Range", "bytes=0-65536")
                .build()
            return chain.proceed(request)
        }
        return chain.proceed(chain.request())
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
                .logger(DebugLogger())
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = {
                                OkHttpClient.Builder()
                                    .addNetworkInterceptor(RequestHeaderInterceptor())
                                    .build()
                            }
                        )
                    )
                    add(AnimatedImageDecoder.Factory())
                    add(SvgDecoder.Factory())
                    add(VideoFrameDecoder.Factory())
                }
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
            MainView(explorerViewModel = explorerViewModel)
        }
    }
}