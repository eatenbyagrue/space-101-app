package com.space101.app

import android.content.ComponentName
import android.content.Context
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
import com.space101.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var radioService: RadioService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            radioService = (binder as RadioService.RadioBinder).getService()
            serviceBound = true
            radioService?.playerStateCallback = { isPlaying, isLoading ->
                runOnUiThread { updateUI(isPlaying, isLoading) }
            }
            radioService?.metadataCallback = { title, artist ->
                runOnUiThread { updateTrackInfo(title, artist) }
            }
            updateUI(radioService?.isPlaying() == true, radioService?.isLoading() == true)
            // Show any metadata that arrived before we bound
            radioService?.let { updateTrackInfo(it.currentTitle, it.currentArtist) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            radioService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        binding.btnPlayPause.setOnClickListener {
            togglePlayback()
        }

        binding.btnWebsite.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.space101fm.org/")))
        }
        binding.btnDonate.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.space101fm.org/donate/")))
        }
    }

    private fun togglePlayback() {
        val service = radioService
        if (service == null) {
            startAndBindService()
            return
        }
        if (service.isPlaying() || service.isLoading()) {
            service.stop()
            unbindService()
        } else {
            service.play()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, RadioService::class.java).apply {
            action = RadioService.ACTION_PLAY
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(
            Intent(this, RadioService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun unbindService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            radioService = null
        }
        updateUI(false, false)
    }

    private fun updateTrackInfo(title: String, artist: String) {
        binding.tvTrackTitle.text = title
        binding.tvTrackArtist.text = artist
        binding.tvTrackTitle.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE
        binding.tvTrackArtist.visibility = if (artist.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateUI(isPlaying: Boolean, isLoading: Boolean) {
        when {
            isLoading -> {
                binding.btnPlayPause.visibility = View.INVISIBLE
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatus.text = "Buffering..."
            }
            isPlaying -> {
                binding.btnPlayPause.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                binding.tvStatus.text = "Live"
            }
            else -> {
                binding.btnPlayPause.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                binding.tvStatus.text = "Tap to listen"
                updateTrackInfo("", "")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-bind if service is already running
        val intent = Intent(this, RadioService::class.java)
        bindService(intent, serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            radioService?.playerStateCallback = null
            radioService?.metadataCallback = null
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
