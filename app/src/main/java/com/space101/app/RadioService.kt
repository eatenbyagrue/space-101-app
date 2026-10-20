package com.space101.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
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
        private const val TAG = "RadioService"
        private const val MAX_RECONNECT_ATTEMPTS = 8 // ~2.5 minutes of backoff
        private const val BT_RESUME_DELAY_MS = 1500L
        private const val ART_SIZE_PX = 320 // keep session/notification bitmaps well under the binder limit
    }

    enum class Status { STOPPED, BUFFERING, PLAYING, RECONNECTING, PAUSED, FAILED }

    var statusCallback: ((Status) -> Unit)? = null
    var metadataCallback: ((title: String, artist: String) -> Unit)? = null

    var currentTitle: String = ""
        private set
    var currentArtist: String = ""
        private set

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var stationArt: Bitmap? = null
    private var pausedDueToNoise = false
    private var reconnectAttempts = 0
    private var reconnectPending = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val binder = RadioBinder()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                val wasActive = status.let { it == Status.PLAYING || it == Status.BUFFERING || it == Status.RECONNECTING }
                pause()
                pausedDueToNoise = wasActive
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (pausedDueToNoise && addedDevices.any { it.isBluetoothOutput() }) {
                // Delay so we request audio focus after other apps (e.g. Spotify) have resumed,
                // ensuring we win the audio focus race on reconnect.
                handler.removeCallbacks(btResume)
                handler.postDelayed(btResume, BT_RESUME_DELAY_MS)
            }
        }
    }

    private val btResume = Runnable {
        if (pausedDueToNoise) play()
    }

    private val reconnect = Runnable {
        reconnectPending = false
        player?.let {
            it.stop() // prepare() is a no-op from STATE_ENDED, so drop back to IDLE first
            it.prepare()
            it.play()
        }
    }

    inner class RadioBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stationArt = BitmapFactory.decodeResource(resources, R.drawable.station_logo)
            ?.let { Bitmap.createScaledBitmap(it, ART_SIZE_PX, ART_SIZE_PX, true) }
        mediaSession = MediaSessionCompat(this, "Space101").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
            })
            isActive = true
        }
        publishMetadata()
        initPlayer()
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun initPlayer() {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(this, httpDataSourceFactory)
        ).createMediaSource(MediaItem.fromUri(STREAM_URL))

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().also { exo ->
                exo.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                exo.setMediaSource(mediaSource)
                exo.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.containsAny(
                                Player.EVENT_PLAYBACK_STATE_CHANGED,
                                Player.EVENT_IS_PLAYING_CHANGED,
                                Player.EVENT_PLAY_WHEN_READY_CHANGED
                            )
                        ) {
                            if (player.isPlaying) reconnectAttempts = 0
                            // A live stream should never end; the server dropped us.
                            if (player.playbackState == Player.STATE_ENDED && player.playWhenReady) {
                                scheduleReconnect("stream ended")
                            }
                            publishStatus()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        scheduleReconnect(error.message)
                    }

                    override fun onMetadata(metadata: Metadata) {
                        for (i in 0 until metadata.length()) {
                            val icy = metadata.get(i) as? IcyInfo ?: continue
                            // A blank title (e.g. during DJ talk) clears the previous track.
                            updateTrack(icy.title.orEmpty())
                            break
                        }
                    }
                })
            }
    }

    val status: Status
        get() {
            val p = player ?: return if (failed) Status.FAILED else Status.STOPPED
            return when {
                failed -> Status.FAILED
                reconnectPending -> Status.RECONNECTING
                !p.playWhenReady -> if (p.playbackState == Player.STATE_IDLE) Status.STOPPED else Status.PAUSED
                p.isPlaying -> Status.PLAYING
                p.playbackState == Player.STATE_BUFFERING ->
                    if (reconnectAttempts > 0) Status.RECONNECTING else Status.BUFFERING
                p.playbackState == Player.STATE_READY -> Status.PAUSED // transient audio focus loss
                else -> Status.STOPPED
            }
        }

    /** Starts playback, always reconnecting so the listener hears the live broadcast, not a stale buffer. */
    fun play() {
        val p = player ?: return
        handler.removeCallbacks(btResume)
        handler.removeCallbacks(reconnect)
        pausedDueToNoise = false
        reconnectPending = false
        reconnectAttempts = 0
        failed = false
        clearTrack() // the stream resends the current title within a second of connecting
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(Status.BUFFERING),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        p.stop()
        p.prepare()
        p.play()
    }

    /** Pauses but keeps the service in the foreground so headset/notification controls can resume it. */
    fun pause() {
        handler.removeCallbacks(btResume)
        handler.removeCallbacks(reconnect)
        pausedDueToNoise = false
        reconnectPending = false
        player?.pause()
        publishStatus()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        pausedDueToNoise = false
        reconnectPending = false
        reconnectAttempts = 0
        player?.stop()
        publishStatus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleReconnect(reason: String?) {
        if (reconnectPending) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Giving up after $reconnectAttempts attempts: $reason")
            failed = true
            stop()
            return
        }
        val delayMs = minOf(1000L shl reconnectAttempts, 30_000L)
        Log.w(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1}): $reason")
        reconnectAttempts++
        reconnectPending = true
        handler.postDelayed(reconnect, delayMs)
        publishStatus()
    }

    private fun updateTrack(raw: String) {
        if (raw.isBlank()) {
            clearTrack()
            return
        }
        val parts = raw.split(" - ", limit = 2)
        currentArtist = if (parts.size == 2) parts[0].trim() else ""
        currentTitle = if (parts.size == 2) parts[1].trim() else raw.trim()
        publishMetadata()
        metadataCallback?.invoke(currentTitle, currentArtist)
        publishStatus()
    }

    private fun clearTrack() {
        if (currentTitle.isEmpty() && currentArtist.isEmpty()) return
        currentTitle = ""
        currentArtist = ""
        publishMetadata()
        metadataCallback?.invoke("", "")
    }

    private fun publishMetadata() {
        mediaSession?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle.ifEmpty { getString(R.string.station_name) })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist.ifEmpty { getString(R.string.station_tagline) })
            // Shown as the album line on car head units and lock screens
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.station_name))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, stationArt)
            .build())
    }

    private fun publishStatus() {
        val s = status
        mediaSession?.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
            )
            .setState(s.toCompatState(), PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        // Only touch the notification while we own it; after stop() it has been removed.
        if (s != Status.STOPPED && s != Status.FAILED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
        }
        statusCallback?.invoke(s)
    }

    private fun Status.toCompatState() = when (this) {
        Status.PLAYING -> PlaybackStateCompat.STATE_PLAYING
        Status.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
        Status.RECONNECTING -> PlaybackStateCompat.STATE_CONNECTING
        Status.PAUSED -> PlaybackStateCompat.STATE_PAUSED
        Status.FAILED -> PlaybackStateCompat.STATE_ERROR
        Status.STOPPED -> PlaybackStateCompat.STATE_STOPPED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(status: Status): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val active = status != Status.PAUSED && status != Status.STOPPED && status != Status.FAILED
        val action = if (active) {
            NotificationCompat.Action(R.drawable.ic_media_stop, getString(R.string.action_stop), serviceIntent(ACTION_STOP))
        } else {
            NotificationCompat.Action(R.drawable.ic_media_play, getString(R.string.action_play), serviceIntent(ACTION_PLAY))
        }

        val text = when (status) {
            Status.BUFFERING -> getString(R.string.status_buffering)
            Status.RECONNECTING -> getString(R.string.status_reconnecting)
            Status.PAUSED -> getString(R.string.status_paused)
            Status.PLAYING -> when {
                currentTitle.isNotEmpty() && currentArtist.isNotEmpty() -> "$currentArtist — $currentTitle"
                currentTitle.isNotEmpty() -> currentTitle
                else -> getString(R.string.notif_live)
            }
            else -> getString(R.string.station_tagline)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.station_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(stationArt)
            .setColor(getColor(R.color.logo_blue))
            .setContentIntent(openIntent)
            .setDeleteIntent(serviceIntent(ACTION_STOP))
            .addAction(action)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(active)
            .setSilent(true)
            .build()
    }

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, RadioService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun AudioDeviceInfo.isBluetoothOutput() =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    override fun onTaskRemoved(rootIntent: Intent?) {
        stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(noisyReceiver)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        statusCallback?.invoke(status)
        statusCallback = null
        metadataCallback = null
        super.onDestroy()
    }
}
