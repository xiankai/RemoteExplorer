package com.example.greetingcard

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.greetingcard.ui.theme.GreetingCardTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.greetingcard.ui.Header
import com.example.greetingcard.ui.List
import com.example.greetingcard.ui.ListViewModel
import com.example.greetingcard.utils.PropertiesLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class ListViewModelFactory(
    private val application: Application,
    private val defaultDispatcher: CoroutineDispatcher
): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ListViewModel(application, defaultDispatcher) as T
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        PropertiesLoader.loadProperties(this)

        val listViewModel: ListViewModel by viewModels {
            ListViewModelFactory(application, Dispatchers.IO)
        }
        super.onCreate(savedInstanceState)
        setContent {
            GreetingCardTheme {
                Scaffold(
                    topBar = { Header(listViewModel) },
                    content =  {
                        innerPadding ->
                            List(modifier = Modifier.padding(innerPadding), listViewModel = listViewModel, )
                    },
                    containerColor = MaterialTheme.colorScheme.background
                )
            }
        }
    }
}