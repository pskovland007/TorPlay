package com.torplay.tv

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class BuiltInPlayerActivity : AppCompatActivity() {

    private lateinit var storage: StorageManager
    private lateinit var playerView: PlayerView
    private lateinit var layoutOsd: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: ProgressBar
    private lateinit var btnRewind: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnForward: TextView
    private lateinit var btnAudioTrack: TextView
    private lateinit var btnSubtitles: TextView

    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector

    private var streamUrl: String = ""
    private var title: String = ""
    private var episodeLabel: String = ""
    private var link: String = ""
    private var fileId: Int = 1
    private var startPositionMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val osdHideRunnable = Runnable { hideOsd() }
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateOsdProgress()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        storage = StorageManager(this)
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Видео" }
        episodeLabel = intent.getStringExtra(EXTRA_EPISODE_LABEL).orEmpty()
        link = intent.getStringExtra(EXTRA_LINK).orEmpty()
        fileId = intent.getIntExtra(EXTRA_FILE_ID, 1)
        startPositionMs = intent.getLongExtra(EXTRA_START_POS_MS, 0L)

        bindViews()
        setupControls()
        initPlayer()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(progressUpdateRunnable)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressUpdateRunnable)
        mainHandler.removeCallbacks(osdHideRunnable)
        savePlaybackProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackProgress()
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (layoutOsd.visibility != View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_MENU -> {
                        showOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekBy(-15_000L)
                        showOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekBy(30_000L)
                        showOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayPause()
                        return true
                    }
                }
            } else {
                scheduleOsdAutoHide()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    hideOsd()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        layoutOsd = findViewById(R.id.layoutOsd)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSubtitles = findViewById(R.id.btnSubtitles)

        tvPlayerTitle.text = if (episodeLabel.isNotBlank()) "$title — $episodeLabel" else title
    }

    private fun setupControls() {
        btnRewind.setOnClickListener {
            seekBy(-15_000L)
            scheduleOsdAutoHide()
        }
        btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleOsdAutoHide()
        }
        btnForward.setOnClickListener {
            seekBy(30_000L)
            scheduleOsdAutoHide()
        }
        btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
            scheduleOsdAutoHide()
        }
        btnSubtitles.setOnClickListener {
            showSubtitleTrackDialog()
            scheduleOsdAutoHide()
        }
    }

    private fun initPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("rus")
                    .setPreferredTextLanguage("rus")
            )
        }

        // ExtensionRendererMode.PREFER enables ffmpeg audio decoding (AC3 / EAC3 / DTS / TrueHD)
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Conservative buffer sizes suitable for Xiaomi Mi Box (2 GB RAM)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                8_000,
                20_000,
                1_500,
                3_000
            )
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        player = exo
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) "Пауза" else "Играть"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    savePlaybackProgress()
                    finish()
                }
            }
        })

        val mediaItem = MediaItem.fromUri(streamUrl)
        exo.setMediaItem(mediaItem)
        if (startPositionMs > 2_000L) {
            exo.seekTo(startPositionMs)
        }
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showOsd() {
        updateOsdProgress()
        layoutOsd.visibility = View.VISIBLE
        btnPlayPause.requestFocus()
        scheduleOsdAutoHide()
    }

    private fun hideOsd() {
        mainHandler.removeCallbacks(osdHideRunnable)
        layoutOsd.visibility = View.GONE
    }

    private fun scheduleOsdAutoHide() {
        mainHandler.removeCallbacks(osdHideRunnable)
        mainHandler.postDelayed(osdHideRunnable, 6_000L)
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        updateOsdProgress()
    }

    private fun updateOsdProgress() {
        val exo = player ?: return
        val cur = exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.coerceAtLeast(0L)
        tvCurrentTime.text = formatDuration(cur)
        tvTotalTime.text = if (dur > 0L) formatDuration(dur) else "--:--"
        if (dur > 0L) {
            seekBar.progress = ((cur * 1000L) / dur).toInt().coerceIn(0, 1000)
        } else {
            seekBar.progress = 0
        }
        btnPlayPause.text = if (exo.isPlaying) "Пауза" else "Играть"
    }

    private data class TrackChoice(
        val label: String,
        val group: Tracks.Group?,
        val trackIndex: Int,
        val disableType: Boolean = false
    )

    private fun showAudioTrackDialog() {
        val exo = player ?: return
        val choices = mutableListOf<TrackChoice>()
        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: "und"
                    val labelName = fmt.label?.takeIf { it.isNotBlank() } ?: lang
                    val codec = fmt.codecs?.substringBefore('.')?.uppercase().orEmpty()
                    val ch = if (fmt.channelCount > 0) " (${fmt.channelCount}ch)" else ""
                    val activeTag = if (group.isTrackSelected(i)) " ✓" else ""
                    val desc = listOfNotNull(labelName, codec.ifBlank { null }).joinToString(" • ") + ch + activeTag
                    choices.add(TrackChoice(desc, group, i))
                }
            }
        }
        if (choices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Аудиодорожки")
                .setMessage("Дополнительные аудиодорожки не найдены")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите аудиодорожку")
            .setItems(labels) { _, which ->
                val chosen = choices[which]
                val grp = chosen.group ?: return@setItems
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, chosen.trackIndex))
                )
            }
            .show()
    }

    private fun showSubtitleTrackDialog() {
        val exo = player ?: return
        val choices = mutableListOf<TrackChoice>()
        choices.add(TrackChoice("Выключить субтитры", null, -1, disableType = true))

        for (group in exo.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: "und"
                    val labelName = fmt.label?.takeIf { it.isNotBlank() } ?: lang
                    val activeTag = if (group.isTrackSelected(i)) " ✓" else ""
                    choices.add(TrackChoice("$labelName$activeTag", group, i))
                }
            }
        }
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Субтитры")
            .setItems(labels) { _, which ->
                val chosen = choices[which]
                val builder = trackSelector.buildUponParameters()
                if (chosen.disableType) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val grp = chosen.group ?: return@setItems
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, chosen.trackIndex))
                }
                trackSelector.setParameters(builder)
            }
            .show()
    }

    private fun savePlaybackProgress() {
        val exo = player ?: return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.coerceAtLeast(0L)
        if (link.isNotBlank() && pos > 1_500L) {
            storage.saveWatchPosition(
                title = title,
                link = link,
                fileId = fileId,
                episodeLabel = episodeLabel,
                positionMs = pos,
                durationMs = dur
            )
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EPISODE_LABEL = "extra_episode_label"
        const val EXTRA_LINK = "extra_link"
        const val EXTRA_FILE_ID = "extra_file_id"
        const val EXTRA_START_POS_MS = "extra_start_pos_ms"
    }
}
