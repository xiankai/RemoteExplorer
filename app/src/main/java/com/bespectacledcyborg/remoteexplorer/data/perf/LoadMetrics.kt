package com.bespectacledcyborg.remoteexplorer.data.perf

import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import io.sentry.metrics.MetricsUnit
import io.sentry.metrics.SentryMetricsParameters
import kotlinx.coroutines.CancellationException

enum class LoadMetric(val key: String) {
    FOLDER_FIRST_PAINT("folder.first_paint"),
    FOLDER_SETTLED("folder.settled"),
    FOLDER_LISTING("folder.listing"),
    FOLDER_COVER("folder.cover"),
    THUMBNAIL_REQUEST("thumbnail.request"),
    THUMBNAIL_QUEUE_WAIT("thumbnail.queue_wait"),
    THUMBNAIL_RENDER("thumbnail.render"),
    TILE_LOAD("tile.load"),
}

interface LoadMetrics {
    fun record(metric: LoadMetric, millis: Long, attributes: Map<String, String> = emptyMap())

    fun folderOpened(attributes: Map<String, String>)

    suspend fun <T> traced(operation: String, description: String, block: suspend () -> T): T

    companion object {
        val NONE = object : LoadMetrics {
            override fun record(metric: LoadMetric, millis: Long, attributes: Map<String, String>) = Unit
            override fun folderOpened(attributes: Map<String, String>) = Unit
            override suspend fun <T> traced(
                operation: String,
                description: String,
                block: suspend () -> T,
            ): T = block()
        }
    }
}

private const val FOLDER_OPEN_IDLE_MILLIS = 3_000L
private const val FOLDER_OPEN_DEADLINE_MILLIS = 60_000L

class SentryLoadMetrics : LoadMetrics {
    @Volatile
    private var folderOpen: ITransaction? = null

    override fun record(metric: LoadMetric, millis: Long, attributes: Map<String, String>) {
        val parameters = SentryMetricsParameters().apply {
            this.attributes = SentryAttributes.fromMap(attributes)
        }
        Sentry.metrics().distribution(
            metric.key, millis.toDouble(), MetricsUnit.Duration.MILLISECOND, parameters,
        )
    }

    override fun folderOpened(attributes: Map<String, String>) {
        folderOpen?.takeUnless { it.isFinished }?.finish(SpanStatus.CANCELLED)
        val options = TransactionOptions().apply {
            isBindToScope = true
            idleTimeout = FOLDER_OPEN_IDLE_MILLIS
            deadlineTimeout = FOLDER_OPEN_DEADLINE_MILLIS
            isWaitForChildren = true
            isTrimEnd = true
        }
        folderOpen = Sentry.startTransaction("folder.open", "ui.load", options).apply {  // stack-reviewer-ignore: an idle transaction finishes itself idleTimeout after its last child span ends, and isTrimEnd puts its end at that child's end, so the duration is the real load with no idle tail. It is not finished at FOLDER_SETTLED on purpose: thumbnail renders that start after the listing settles are the slow part this trace exists to show. Device logcat shows each folder.open captured ~3 s after its last span with nothing left bound.
            attributes.forEach { (key, value) -> setTag(key, value) }
        }
    }

    override suspend fun <T> traced(
        operation: String,
        description: String,
        block: suspend () -> T,
    ): T {
        val span = folderOpen?.takeUnless { it.isFinished }?.startChild(operation, description)
            ?: return block()
        return try {
            block().also { span.finish(SpanStatus.OK) }
        } catch (e: CancellationException) {
            span.finish(SpanStatus.CANCELLED)
            throw e
        } catch (e: Exception) {
            span.throwable = e
            span.finish(SpanStatus.INTERNAL_ERROR)
            throw e
        }
    }
}
