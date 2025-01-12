import com.example.remoteexplorer.data.db.Connection
import com.example.remoteexplorer.data.db.ConnectionDao
import kotlinx.coroutines.flow.Flow

class ConnectionDataSource(private val connectionDao: ConnectionDao) {
    fun getConnections(): Flow<List<Connection>> {
        return connectionDao.getConnections()
    }

    suspend fun addConnection(connection: Connection) {
        connectionDao.addConnection(connection)
    }

    suspend fun deleteConnection(connection: Connection) {
        connectionDao.deleteConnection(connection)
    }
}