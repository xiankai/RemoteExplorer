package com.bespectacledcyborg.remoteexplorer.data

import com.bespectacledcyborg.remoteexplorer.data.db.FolderSort
import com.bespectacledcyborg.remoteexplorer.data.db.FolderSortDao
import kotlinx.coroutines.flow.Flow

class FolderSortRepository(
    private val folderSortDao: FolderSortDao,
) {
    fun observeAll(): Flow<List<FolderSort>> = folderSortDao.observeAll()

    suspend fun choose(folder: FileIdentity, sort: SortType) {
        folderSortDao.upsert(FolderSort(folder.connectionId, folder.path, sort))
    }

    suspend fun clear(folder: FileIdentity) {
        folderSortDao.delete(folder.connectionId, folder.path)
    }
}
