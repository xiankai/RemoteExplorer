import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.remoteexplorer.data.TagRepository

const val batchSize = 100

class ScanTagsWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val tagRepository: TagRepository,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val connectionId = inputData.getInt("CONNECTION_ID", 0)
        val forceRemote = inputData.getBoolean("FORCE_REMOTE", false)

        if (connectionId == 0) {
            Log.e("TagWorker", "No CONNECTION_ID passed in")
            return Result.failure()
        }

        return try {
            val scannedTagCount = tagRepository.scanTags(connectionId, forceRemote, batchSize)
            if (scannedTagCount == batchSize) {
                // Since we hit the max limit, there may be more requests to process
                val workRequest = OneTimeWorkRequestBuilder<ScanTagsWorker>()
                    .setInputData(
                        workDataOf(
                            "CONNECTION_ID" to connectionId,
                            "FORCE_REMOTE" to forceRemote,
                        )
                    )
                    .build()

                WorkManager.getInstance(applicationContext).enqueue(workRequest)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("TagWorker", "scanTags failed", e)
            Result.failure()
        }
    }
}
