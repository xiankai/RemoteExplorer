package com.bespectacledcyborg.remoteexplorer.data.views

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.bespectacledcyborg.remoteexplorer.R
import com.bespectacledcyborg.remoteexplorer.data.FileIdentity
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val STREAMING_IDLE_MILLIS = 10 * 60_000L
private const val STREAMING_CHECK_MILLIS = 60_000L

class StreamingActivity(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : PlaybackListener {
    @Volatile
    private var lastActiveAt = clock()

    override fun onServed(
        canonical: FileIdentity,
        served: FileIdentity,
        first: Long,
        last: Long,
        totalLength: Long,
    ) = touch()

    fun touch() {
        lastActiveAt = clock()
    }

    fun isIdle(): Boolean = clock() - lastActiveAt >= STREAMING_IDLE_MILLIS

    companion object {
        val shared by lazy { StreamingActivity() }
    }
}

class ExternalPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleWatch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            notification(intent?.getStringExtra(EXTRA_TITLE)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        StreamingActivity.shared.touch()
        if (idleWatch?.isActive != true) {
            idleWatch = scope.launch {  // stack-reviewer-ignore: a stop here is final on purpose. Android refuses to start a mediaPlayback foreground service from the background, so traffic arriving after REx has left the screen cannot re-arm it; the next handoff from REx, made while it is in front, starts it again. Ten minutes with no byte served is a paused or closed player.
                while (!StreamingActivity.shared.isIdle()) delay(STREAMING_CHECK_MILLIS)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.streaming_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.movie_24px)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(title)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "external-playback"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "title"

        fun stop(context: Context) {
            context.stopService(Intent(context, ExternalPlaybackService::class.java))
        }

        fun start(context: Context, title: String) {
            val intent = Intent(context, ExternalPlaybackService::class.java)
                .putExtra(EXTRA_TITLE, title)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Log.w("ExternalPlaybackService", "Could not keep the server awake for $title", e)
                Sentry.captureException(e)
            }
        }
    }
}
