package com.downtube.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.downtube.app.databinding.ActivityPlayerBinding

/**
 * Built-in player for finished downloads: seek, play/pause, playback speed,
 * mute and picture-free fullscreen via the device's own controls.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private val speeds = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 1
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }

        binding.playerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true

        binding.closeButton.setOnClickListener { finish() }

        binding.speedButton.text = speedLabel()
        binding.speedButton.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            exo.setPlaybackSpeed(speeds[speedIndex])
            binding.speedButton.text = speedLabel()
        }

        binding.muteButton.setOnClickListener {
            muted = !muted
            exo.volume = if (muted) 0f else 1f
            binding.muteButton.text = getString(if (muted) R.string.unmute else R.string.mute)
        }
    }

    private fun speedLabel(): String {
        val speed = speeds[speedIndex]
        return if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
