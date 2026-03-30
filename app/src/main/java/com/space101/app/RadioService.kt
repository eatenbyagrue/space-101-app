package com.space101.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.icy.IcyInfo

class RadioService : Service() {

    companion object {
        const val STREAM_URL = "http://kmgp.broadcasttool.stream/xstream.mp3"
        const val CHANNEL_ID = "radio_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.space101.app.PLAY"
        const val ACTION_STOP = "com.space101.app.STOP"
    }

    private var player: ExoPlayer? = null
    private val binder = RadioBinder()

    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initPlayer()
    }

    private fun initPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        ).createMediaSource(MediaItem.fromUri(STREAM_URL))

        player = ExoPlayer.Builder(this).build().also { exo ->
            exo.setMediaSource(mediaSource)
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotification(isPlaying)
                    playerStateCallback?.invoke(isPlaying, false)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    val loading = state == Player.STATE_BUFFERING
                    playerStateCallback?.invoke(exo.isPlaying, loading)
                }
                override fun onMetadata(metadata: Metadata) {
                    for (i in 0 until metadata.length()) {
                        val entry = metadata.get(i)
                        if (entry is IcyInfo && entry.title != null) {
                            parseIcyTitle(entry.title!!)
                            break
                        }
                    }
                }
            })
        }
    }

    var playerStateCallback: ((isPlaying: Boolean, isLoading: Boolean) -> Unit)? = null
    var metadataCallback: ((title: String, artist: String) -> Unit)? = null

    var currentTitle: String = ""
        private set
    var currentArtist: String = ""
        private set

    private fun parseIcyTitle(raw: String) {
        val parts = raw.split(" - ", limit = 2)
        currentArtist = if (parts.size == 2) parts[0].trim() else ""
        currentTitle = if (parts.size == 2) parts[1].trim() else raw.trim()
        metadataCallback?.invoke(currentTitle, currentArtist)
        updateNotification(player?.isPlaying == true)
    }

    fun play() {
        player?.let {
            it.prepare()
            it.play()
        }
        startForeground(NOTIFICATION_ID, buildNotification(true))
    }

    fun stop() {
        player?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun isLoading(): Boolean = player?.playbackState == Player.STATE_BUFFERING

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(isPlaying: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(isPlaying))
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RadioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notifText = when {
            !isPlaying -> "Buffering..."
            currentTitle.isNotEmpty() && currentArtist.isNotEmpty() -> "$currentArtist — $currentTitle"
            currentTitle.isNotEmpty() -> currentTitle
            else -> "Live"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Space 101.1 FM")
            .setContentText(notifText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(isPlaying)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Radio Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Space 101.1 FM playback controls"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
