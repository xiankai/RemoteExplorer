package com.example.greetingcard.data

import android.content.Context
import android.provider.ContactsContract.Data
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File
import java.io.InputStream
import java.io.OutputStream

object FileSerializer : Serializer<File> {
    override val defaultValue: File = File.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): File {
        try {
            return File.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: File,
        output: OutputStream) = t.writeTo(output)
}

val Context.fileDataStore: DataStore<File> by dataStore(
    fileName = "files.pb",
    serializer = FileSerializer
)




class FileRepository {
    private val dataStore: DataStore<File>

    suspend fun getFiles(): List<File> {
    }
}