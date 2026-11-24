package com.bespectacledcyborg.remoteexplorer.data

interface EntryTree {
    fun exists(connectionId: ConnectionId, path: String): Boolean

    fun isFolder(connectionId: ConnectionId, path: String): Boolean

    fun namesIn(connectionId: ConnectionId, folder: String): List<String>

    fun createFolder(connectionId: ConnectionId, path: String)

    fun move(connectionId: ConnectionId, from: String, to: String)

    fun filesUnder(connectionId: ConnectionId, path: String, cap: Int): List<String>
}
