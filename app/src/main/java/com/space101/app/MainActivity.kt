package com.space101.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.space101.app.RadioService.Status
import com.space101.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var radioService: RadioService? = null
    private var serviceBound = false
    private var pulseAnimator: AnimatorSet? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as RadioService.RadioBinder).getService()
            radioService = service
            // Service callbacks are delivered on the main thread.
            service.statusCallback = ::updateUI
            service.metadataCallback = ::updateTrackInfo
            updateUI(service.status)
            // Show any metadata that arrived before we bound
            updateTrackInfo(service.currentTitle, service.currentArtist)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            radioService = null
            updateUI(Status.STOPPED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        requestNotificationPermission()

        binding.btnPlayStop.setOnClickListener { togglePlayback() }
        binding.tvStatus.setOnClickListener { requestPlay() } // "↻ Live": jump back to the live edge
        binding.btnWebsite.setOnClickListener { openUrl("https://www.space101fm.org/") }
        binding.btnDonate.setOnClickListener { openUrl("https://www.space101fm.org/donate/") }

        updateUI(Status.STOPPED)
    }

    private fun togglePlayback() {
        when (radioService?.status) {
            Status.PLAYING, Status.BUFFERING, Status.RECONNECTING -> radioService?.stop()
            else -> requestPlay()
        }
    }

    /**
     * Always go through startForegroundService so the service is "started", not merely bound;
     * otherwise it would be destroyed (and playback cut) as soon as the activity unbinds.
     */
    private fun requestPlay() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, RadioService::class.java).setAction(RadioService.ACTION_PLAY)
        )
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun updateTrackInfo(title: String, artist: String) {
        binding.tvTrackTitle.text = title
        binding.tvTrackArtist.text = artist
        binding.tvTrackTitle.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvTrackArtist.visibility = if (artist.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvTrackTitle.isSelected = true // starts the marquee
    }

    private fun updateUI(status: Status) {
        val active = status == Status.PLAYING || status == Status.BUFFERING || status == Status.RECONNECTING
        binding.btnPlayStop.setImageResource(if (active) R.drawable.ic_media_stop else R.drawable.ic_media_play)
        binding.btnPlayStop.contentDescription = getString(if (active) R.string.action_stop else R.string.action_play)

        binding.tvStatus.setText(when (status) {
            Status.PLAYING -> R.string.status_live
            Status.BUFFERING -> R.string.status_buffering
            Status.RECONNECTING -> R.string.status_reconnecting
            Status.PAUSED -> R.string.status_paused
            Status.FAILED -> R.string.status_failed
            Status.STOPPED -> R.string.status_idle
        })

        val live = status == Status.PLAYING
        binding.tvStatus.isClickable = live
        binding.tvStatus.contentDescription = if (live) getString(R.string.sync_to_live) else null
        if (live) binding.tvStatus.setBackgroundResource(R.drawable.link_bg) else binding.tvStatus.background = null

        if (live) startPulseAnimation() else stopPulseAnimation()
        if (!active && status != Status.PAUSED) updateTrackInfo("", "")
    }

    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        val ring = binding.pulseRing
        fun pulse(property: android.util.Property<View, Float>, vararg values: Float) =
            ObjectAnimator.ofFloat(ring, property, *values).apply {
                duration = 2000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            }
        pulseAnimator = AnimatorSet().apply {
            playTogether(pulse(View.SCALE_X, 1f, 1.14f), pulse(View.SCALE_Y, 1f, 1.14f), pulse(View.ALPHA, 0.85f, 0.2f))
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.pulseRing.scaleX = 1f
        binding.pulseRing.scaleY = 1f
        binding.pulseRing.alpha = 1f
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onStart() {
        super.onStart()
        // Flags = 0: attach to the service whenever it is running, but never create it just by binding.
        serviceBound = bindService(Intent(this, RadioService::class.java), serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        stopPulseAnimation()
        if (serviceBound) {
            radioService?.statusCallback = null
            radioService?.metadataCallback = null
            unbindService(serviceConnection)
            serviceBound = false
            radioService = null
        }
    }
}
