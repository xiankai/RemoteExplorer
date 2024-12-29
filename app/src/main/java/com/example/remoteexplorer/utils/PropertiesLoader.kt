package com.example.remoteexplorer.utils

import android.content.Context
import java.util.Properties

object PropertiesLoader {
    private const val PROPERTIES_FILE_NAME = "app.properties"
    private val properties = Properties()

    fun loadProperties(context: Context) {
        context.assets.open(PROPERTIES_FILE_NAME).use { inputStream ->
            properties.load(inputStream)
        }
    }

    fun getProperty(key: String, defaultValue: String = ""): String {
        return properties.getProperty(key, defaultValue)
    }
}
