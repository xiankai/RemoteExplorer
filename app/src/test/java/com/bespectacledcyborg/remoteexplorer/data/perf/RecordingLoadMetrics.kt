package com.bespectacledcyborg.remoteexplorer.data.perf

class RecordingLoadMetrics : LoadMetrics {
    data class Recorded(val metric: LoadMetric, val millis: Long, val attributes: Map<String, String>)

    val recorded = mutableListOf<Recorded>()
    val foldersOpened = mutableListOf<Map<String, String>>()

    override fun record(metric: LoadMetric, millis: Long, attributes: Map<String, String>) {
        recorded += Recorded(metric, millis, attributes)
    }

    override fun folderOpened(attributes: Map<String, String>) {
        foldersOpened += attributes
    }

    override suspend fun <T> traced(
        operation: String,
        description: String,
        block: suspend () -> T,
    ): T = block()
}
