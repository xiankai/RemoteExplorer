package com.example.remoteexplorer.data

import ConnectionDataSource
import com.example.remoteexplorer.data.db.Connection
import kotlinx.coroutines.flow.Flow

class ConnectionRepository (
    private val connectionDataSource: ConnectionDataSource,
) {
    fun getConnections(): Flow<List<Connection>> {
        return connectionDataSource.getConnections()
    }

    suspend fun addConnection(connection: Connection) {
        connectionDataSource.addConnection(connection)
    }

    suspend fun deleteConnection(connection: Connection) {
        connectionDataSource.deleteConnection(connection)
    }
}