package com.example.remoteexplorer.data

import LocalDatabaseDataSource
import android.util.Log
import com.adobe.internal.xmp.XMPMetaFactory
import com.example.remoteexplorer.data.db.LocalTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Paths

class TagRepository (
    private val fileRepository: FileRepository,
    private val localDatabaseDataSource: LocalDatabaseDataSource,
    private val dispatcher: CoroutineDispatcher
) {
    suspend fun scanTags(connectionId: Int, forceRemote: Boolean) {
        withContext(dispatcher) {
            val tagFiles = localDatabaseDataSource.getTagFiles(connectionId, forceRemote)
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

                // Getting the corresponding file
                val fileNamespace = "http://ns.adobe.com/xap/1.0/mm/"
                val filePropertyName = "DerivedFrom"

                val derivedFrom = xmpMeta.getPropertyString(fileNamespace, filePropertyName)
                if (derivedFrom != null) {
                    localDatabaseDataSource.updateFileTags(connectionId, it.parentPath, it.filename, derivedFrom, tags)
                } else {
                    Log.d("TagRepository", "No derivedFrom found for $tagPath")
                }
            }
        }
    }

    suspend fun getTags(): List<LocalTag> {
        return localDatabaseDataSource.getTags()
    }
}