package com.example.remoteexplorer.data

import LocalDatabaseDataSource
import android.util.Log
import com.adobe.internal.xmp.XMPMetaFactory
import com.example.remoteexplorer.data.db.LocalTagWithMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Paths

const val tagPrefix = "Tag: "

class TagRepository (
    private val fileRepository: FileRepository,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    suspend fun scanTags(connectionId: Int, forceRemote: Boolean) {
        withContext(dispatcher) {
            val tagFiles = localDatabaseDataSource.getTagFiles(connectionId, forceRemote)
            Log.d("TagRepository", "Scanning ${tagFiles.size} tags")
            tagFiles.forEach {
                val tagPath = Paths.get(it.parentPath, it.filename).toString()
                val tagFileHandle = fileRepository.openFileDirectly(tagPath)
                val xmpMeta = XMPMetaFactory.parse(tagFileHandle.inputStream)

                // Using Dublin Core namespace as they are the most common metadata category
                val tagNamespace = "http://purl.org/dc/elements/1.1/"
                val tagPropertyName = "subject"

                // XML array starts from 1-index
                val tags = mutableListOf<String>()
                for (i in 1..xmpMeta.countArrayItems(tagNamespace, tagPropertyName)) {
                    val tag = xmpMeta.getArrayItem(tagNamespace, tagPropertyName, i).value
                    tags.add(tag)
                }

                localDatabaseDataSource.updateFileTags(connectionId, it, tags)
            }
            Log.d("TagRepository", "Scanned ${tagFiles.size} tags")
        }
    }

    suspend fun getTags(): List<LocalTagWithMetadata> {
        return localDatabaseDataSource.getTags()
    }
}