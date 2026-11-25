package com.torplay.tv

import android.app.AlertDialog
import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(UnstableApi::class)
class BuiltInPlayerActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var storage: StorageManager
    private lateinit var playerPrefs: PlayerPrefs
    private lateinit var torrServeClient: TorrServeClient

    private lateinit var playerView: PlayerView
    private lateinit var tvQuickHud: TextView
    private lateinit var layoutOsd: LinearLayout
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvStreamInfo: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: ProgressBar
    private lateinit var btnRewind: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnForward: TextView
    private lateinit var btnAudioTrack: TextView
    private lateinit var btnSubtitles: TextView
    private lateinit var btnResizeMode: TextView
    private lateinit var layoutSeriesRow: LinearLayout
    private lateinit var btnEpisodes: TextView
    private lateinit var btnNextEpisode: TextView
    private lateinit var btnAutoNext: TextView

    private lateinit var layoutNextEpisode: LinearLayout
    private lateinit var tvNextEpisodeHeader: TextView
    private lateinit var tvNextEpisodeName: TextView
    private lateinit var btnNextCancel: TextView
    private lateinit var btnNextPlayNow: TextView

    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector

    private var streamUrl: String = ""
    private var title: String = ""
    private var episodeLabel: String = ""
    private var link: String = ""
    private var torrentHash: String = ""
    private var fileId: Int = 1
    private var startPositionMs: Long = 0L

    private var episodes: List<TorrServeFile> = emptyList()
    private var currentEpisodeIndex: Int = -1
    private var torrentStatsSummary: String = ""
    private var lastTorrStatsFetchMs: Long = 0L
    private var statsJob: Job? = null

    private var resizeModeIndex: Int = 0
    private var autoNextEnabled: Boolean = true
    private var appliedSavedTracksForItem: Boolean = false
    private var pendingResumeSanityCheck: Boolean = false

    private var nextCountdownSec: Int = 0
    private var nextCardDismissedForCurrent: Boolean = false
    private var nextCardActive: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val osdHideRunnable = Runnable { hideOsd() }
    private val quickHudHideRunnable = Runnable { tvQuickHud.visibility = View.GONE }

    private val nextCountdownRunnable = object : Runnable {
        override fun run() {
            if (!nextCardActive) return
            if (nextCountdownSec <= 0) {
                hideNextEpisodeCard(dismissed = false)
                playNextEpisode(autoTriggered = true)
                return
            }
            tvNextEpisodeHeader.text = "Следующая серия через $nextCountdownSec сек"
            nextCountdownSec--
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateOsdProgress()
            checkAutoNextTrigger()
            if (layoutOsd.visibility == View.VISIBLE) {
                maybePollTorrServeStats()
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        storage = StorageManager(this)
        playerPrefs = PlayerPrefs(this)
        torrServeClient = TorrServeClient(storage)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Видео" }
        episodeLabel = intent.getStringExtra(EXTRA_EPISODE_LABEL).orEmpty()
        link = intent.getStringExtra(EXTRA_LINK).orEmpty()
        fileId = intent.getIntExtra(EXTRA_FILE_ID, 1)
        startPositionMs = intent.getLongExtra(EXTRA_START_POS_MS, 0L)
        torrentHash = extractHashFromStreamOrLink(streamUrl, link)

        resizeModeIndex = playerPrefs.resizeModeIndex
        autoNextEnabled = playerPrefs.autoNextEpisode

        bindViews()
        setupControls()
        applyResizeMode(showFeedback = false)
        updateAutoNextUi()
        updateSeriesControlsUi()
        initPlayer()
        loadEpisodesMetadata()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(progressUpdateRunnable)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressUpdateRunnable)
        mainHandler.removeCallbacks(osdHideRunnable)
        mainHandler.removeCallbacks(quickHudHideRunnable)
        mainHandler.removeCallbacks(nextCountdownRunnable)
        savePlaybackProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePlaybackProgress()
        uiScope.cancel()
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (nextCardActive) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    hideNextEpisodeCard(dismissed = true)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

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
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val step = acceleratedSeekStepMs(event.repeatCount)
                        seekBy(-step, showQuickHud = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val step = acceleratedSeekStepMs(event.repeatCount)
                        seekBy(step, showQuickHud = true)
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayPause()
                        showQuickHudMessage(if (player?.isPlaying == true) "▶ Воспроизведение" else "❚❚ Пауза")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player?.play()
                        showQuickHudMessage("▶ Воспроизведение")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player?.pause()
                        showQuickHudMessage("❚❚ Пауза")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (hasNextEpisode()) {
                            playNextEpisode(autoTriggered = false)
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (hasPreviousEpisode()) {
                            playPreviousEpisode()
                            return true
                        }
                    }
                }
            } else {
                scheduleOsdAutoHide()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    hideOsd()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    togglePlayPause()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun acceleratedSeekStepMs(repeatCount: Int): Long {
        return when {
            repeatCount >= 15 -> 120_000L
            repeatCount >= 8 -> 60_000L
            repeatCount >= 3 -> 30_000L
            else -> 15_000L
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        tvQuickHud = findViewById(R.id.tvQuickHud)
        layoutOsd = findViewById(R.id.layoutOsd)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvStreamInfo = findViewById(R.id.tvStreamInfo)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnResizeMode = findViewById(R.id.btnResizeMode)
        layoutSeriesRow = findViewById(R.id.layoutSeriesRow)
        btnEpisodes = findViewById(R.id.btnEpisodes)
        btnNextEpisode = findViewById(R.id.btnNextEpisode)
        btnAutoNext = findViewById(R.id.btnAutoNext)

        layoutNextEpisode = findViewById(R.id.layoutNextEpisode)
        tvNextEpisodeHeader = findViewById(R.id.tvNextEpisodeHeader)
        tvNextEpisodeName = findViewById(R.id.tvNextEpisodeName)
        btnNextCancel = findViewById(R.id.btnNextCancel)
        btnNextPlayNow = findViewById(R.id.btnNextPlayNow)

        updateHeaderTitle()
    }

    private fun updateHeaderTitle() {
        val epCounter = if (episodes.size > 1 && currentEpisodeIndex >= 0) {
            " [${currentEpisodeIndex + 1}/${episodes.size}]"
        } else {
            ""
        }
        tvPlayerTitle.text = if (episodeLabel.isNotBlank()) {
            "$title — $episodeLabel$epCounter"
        } else {
            "$title$epCounter"
        }
    }

    private fun setupControls() {
        btnRewind.setOnClickListener {
            seekBy(-15_000L, showQuickHud = false)
            scheduleOsdAutoHide()
        }
        btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleOsdAutoHide()
        }
        btnForward.setOnClickListener {
            seekBy(30_000L, showQuickHud = false)
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
        btnResizeMode.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % 4
            playerPrefs.resizeModeIndex = resizeModeIndex
            applyResizeMode(showFeedback = true)
            scheduleOsdAutoHide()
        }
        btnEpisodes.setOnClickListener {
            showEpisodeSwitcherDialog()
            scheduleOsdAutoHide()
        }
        btnNextEpisode.setOnClickListener {
            playNextEpisode(autoTriggered = false)
        }
        btnAutoNext.setOnClickListener {
            autoNextEnabled = !autoNextEnabled
            playerPrefs.autoNextEpisode = autoNextEnabled
            updateAutoNextUi()
            if (!autoNextEnabled && nextCardActive) {
                hideNextEpisodeCard(dismissed = true)
            }
            scheduleOsdAutoHide()
        }
        btnNextPlayNow.setOnClickListener {
            hideNextEpisodeCard(dismissed = false)
            playNextEpisode(autoTriggered = false)
        }
        btnNextCancel.setOnClickListener {
            hideNextEpisodeCard(dismissed = true)
        }
    }

    private fun applyResizeMode(showFeedback: Boolean) {
        val mode = when (resizeModeIndex) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val labelRes = when (resizeModeIndex) {
            1 -> R.string.player_resize_zoom
            2 -> R.string.player_resize_fill
            3 -> R.string.player_resize_width
            else -> R.string.player_resize_fit
        }
        playerView.resizeMode = mode
        val label = getString(labelRes)
        btnResizeMode.text = label
        if (showFeedback) {
            showQuickHudMessage(label)
        }
    }

    private fun updateAutoNextUi() {
        btnAutoNext.isSelected = autoNextEnabled
        btnAutoNext.text = getString(
            if (autoNextEnabled) R.string.player_auto_next_on else R.string.player_auto_next_off
        )
    }

    private fun updateSeriesControlsUi() {
        val multi = episodes.size > 1
        layoutSeriesRow.visibility = if (multi) View.VISIBLE else View.GONE

        if (multi) {
            val posLabel = if (currentEpisodeIndex >= 0) "${currentEpisodeIndex + 1}/${episodes.size}" else "${episodes.size}"
            btnEpisodes.text = "${getString(R.string.player_episodes_btn)} ($posLabel)"

            val hasNext = hasNextEpisode()
            btnNextEpisode.visibility = if (hasNext) View.VISIBLE else View.GONE

            btnEpisodes.nextFocusRightId = if (hasNext) R.id.btnNextEpisode else R.id.btnAutoNext
            btnAutoNext.nextFocusLeftId = if (hasNext) R.id.btnNextEpisode else R.id.btnEpisodes

            btnRewind.nextFocusDownId = R.id.btnEpisodes
            btnPlayPause.nextFocusDownId = R.id.btnEpisodes
            btnForward.nextFocusDownId = if (hasNext) R.id.btnNextEpisode else R.id.btnEpisodes
            btnAudioTrack.nextFocusDownId = R.id.btnAutoNext
            btnSubtitles.nextFocusDownId = R.id.btnAutoNext
            btnResizeMode.nextFocusDownId = R.id.btnAutoNext
        } else {
            btnRewind.nextFocusDownId = R.id.btnRewind
            btnPlayPause.nextFocusDownId = R.id.btnPlayPause
            btnForward.nextFocusDownId = R.id.btnForward
            btnAudioTrack.nextFocusDownId = R.id.btnAudioTrack
            btnSubtitles.nextFocusDownId = R.id.btnSubtitles
            btnResizeMode.nextFocusDownId = R.id.btnResizeMode
        }
        updateHeaderTitle()
    }

    private fun initPlayer() {
        val prefAudio = playerPrefs.preferredAudioLang.ifBlank { "rus" }
        val prefSub = playerPrefs.preferredSubLang.ifBlank { "rus" }
        trackSelector = DefaultTrackSelector(this).apply {
            val builder = buildUponParameters()
                .setPreferredAudioLanguage(prefAudio)
                .setPreferredTextLanguage(prefSub)
            if (playerPrefs.subtitlesDisabled) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            setParameters(builder)
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
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

            override fun onTracksChanged(tracks: Tracks) {
                if (!appliedSavedTracksForItem) {
                    applyRememberedTracks(tracks)
                }
                updateStreamInfoText()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (pendingResumeSanityCheck) {
                        pendingResumeSanityCheck = false
                        val dur = exo.duration
                        if (dur > 15_000L && exo.currentPosition >= dur - 8_000L) {
                            exo.seekTo(0L)
                        }
                    }
                    updateStreamInfoText()
                } else if (playbackState == Player.STATE_ENDED) {
                    savePlaybackProgress(completed = true)
                    if (autoNextEnabled && hasNextEpisode()) {
                        if (!nextCardActive) {
                            showNextEpisodeCard(countdownSec = 5)
                        }
                    } else {
                        finish()
                    }
                }
            }
        })

        prepareMedia(streamUrl, startPositionMs)
    }

    private fun prepareMedia(url: String, seekPosMs: Long) {
        val exo = player ?: return
        appliedSavedTracksForItem = false
        nextCardDismissedForCurrent = false
        hideNextEpisodeCard(dismissed = false)

        // Reset overrides from previous episode while preserving user preferences
        val resetBuilder = trackSelector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(playerPrefs.preferredAudioLang.ifBlank { "rus" })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, playerPrefs.subtitlesDisabled)
        if (!playerPrefs.subtitlesDisabled && playerPrefs.preferredSubLang.isNotBlank()) {
            resetBuilder.setPreferredTextLanguage(playerPrefs.preferredSubLang)
        }
        trackSelector.setParameters(resetBuilder)

        val mediaItem = MediaItem.fromUri(url)
        exo.setMediaItem(mediaItem)
        if (seekPosMs > 2_000L) {
            pendingResumeSanityCheck = true
            exo.seekTo(seekPosMs)
        } else {
            pendingResumeSanityCheck = false
        }
        exo.prepare()
        exo.playWhenReady = true
        updateStreamInfoText()
    }

    private fun loadEpisodesMetadata() {
        if (torrentHash.isBlank()) return
        uiScope.launch {
            val info = runCatching { torrServeClient.getTorrentInfo(torrentHash) }.getOrNull() ?: return@launch
            updateTorrentStatsSummary(info)
            val vids = torrServeClient.filterAndSortVideos(info.files)
            val resolved = if (vids.isNotEmpty()) vids else info.files
            if (resolved.isNotEmpty()) {
                episodes = resolved
                currentEpisodeIndex = episodes.indexOfFirst { it.id == fileId }
                if (episodeLabel.isBlank() && currentEpisodeIndex >= 0 && episodes.size > 1) {
                    episodeLabel = episodes[currentEpisodeIndex].fileName
                }
                updateSeriesControlsUi()
            }
            updateStreamInfoText()
        }
    }

    private fun maybePollTorrServeStats() {
        if (torrentHash.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastTorrStatsFetchMs < 2_500L) return
        if (statsJob?.isActive == true) return
        lastTorrStatsFetchMs = now
        statsJob = uiScope.launch {
            val info = runCatching { torrServeClient.getTorrentInfo(torrentHash) }.getOrNull() ?: return@launch
            updateTorrentStatsSummary(info)
            updateStreamInfoText()
        }
    }

    private fun updateTorrentStatsSummary(info: TorrServeInfo) {
        val parts = mutableListOf<String>()
        if (info.downloadSpeed > 0.0) {
            parts.add(info.formattedSpeed())
        }
        if (info.totalPeers > 0 || info.activePeers > 0) {
            parts.add("Пиры ${info.activePeers}/${info.totalPeers}")
        }
        torrentStatsSummary = parts.joinToString(" • ")
    }

    private fun applyRememberedTracks(tracks: Tracks) {
        var hasAudio = false
        var hasText = false
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.length > 0) hasAudio = true
            if (group.type == C.TRACK_TYPE_TEXT && group.length > 0) hasText = true
        }
        if (!hasAudio && !hasText) return
        appliedSavedTracksForItem = true

        val builder = trackSelector.buildUponParameters()
        var changed = false

        val savedAudioLabel = playerPrefs.preferredAudioLabel
        val savedAudioLang = playerPrefs.preferredAudioLang
        if (hasAudio && (savedAudioLabel.isNotBlank() || savedAudioLang.isNotBlank())) {
            val match = findBestMatchingTrack(tracks, C.TRACK_TYPE_AUDIO, savedAudioLabel, savedAudioLang)
            if (match != null) {
                builder.setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                changed = true
            }
        }

        if (playerPrefs.subtitlesDisabled) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            changed = true
        } else if (hasText) {
            val savedSubLabel = playerPrefs.preferredSubLabel
            val savedSubLang = playerPrefs.preferredSubLang
            if (savedSubLabel.isNotBlank() || savedSubLang.isNotBlank()) {
                val match = findBestMatchingTrack(tracks, C.TRACK_TYPE_TEXT, savedSubLabel, savedSubLang)
                if (match != null) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                    changed = true
                }
            }
        }

        if (changed) {
            trackSelector.setParameters(builder)
        }
    }

    private fun findBestMatchingTrack(
        tracks: Tracks,
        trackType: Int,
        preferredLabel: String,
        preferredLang: String
    ): Pair<Tracks.Group, Int>? {
        val wantLabel = preferredLabel.trim().lowercase(Locale.ROOT)
        val wantLang = preferredLang.trim().lowercase(Locale.US)
        var langFallback: Pair<Tracks.Group, Int>? = null

        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                val lbl = fmt.label?.trim()?.lowercase(Locale.ROOT).orEmpty()
                val lng = fmt.language?.trim()?.lowercase(Locale.US).orEmpty()

                if (wantLabel.isNotBlank() && lbl.isNotBlank() && lbl == wantLabel) {
                    if (wantLang.isBlank() || lng == wantLang) {
                        return group to i
                    }
                }
                if (langFallback == null && wantLang.isNotBlank() && lng.isNotBlank() &&
                    (lng == wantLang || lng.startsWith(wantLang.take(2)))
                ) {
                    langFallback = group to i
                }
            }
        }
        return langFallback
    }

    private fun hasNextEpisode(): Boolean {
        return episodes.size > 1 && currentEpisodeIndex in 0 until (episodes.size - 1)
    }

    private fun hasPreviousEpisode(): Boolean {
        return episodes.size > 1 && currentEpisodeIndex > 0
    }

    private fun playNextEpisode(autoTriggered: Boolean) {
        if (!hasNextEpisode()) {
            if (autoTriggered) finish()
            return
        }
        switchToEpisode(currentEpisodeIndex + 1)
    }

    private fun playPreviousEpisode() {
        if (!hasPreviousEpisode()) return
        switchToEpisode(currentEpisodeIndex - 1)
    }

    private fun switchToEpisode(targetIndex: Int) {
        if (targetIndex !in episodes.indices) return
        savePlaybackProgress()

        val nextFile = episodes[targetIndex]
        currentEpisodeIndex = targetIndex
        fileId = nextFile.id
        episodeLabel = nextFile.fileName

        if (torrentHash.isNotBlank()) {
            storage.markEpisodeWatched(torrentHash, nextFile.id)
        }

        val hashOrLink = torrentHash.ifBlank { link }
        streamUrl = torrServeClient.buildStreamUrl(hashOrLink, nextFile.id, nextFile.fileName)
        startPositionMs = storage.getSavedPositionMs(link, nextFile.id)

        updateSeriesControlsUi()
        hideOsd()
        showQuickHudMessage("▶ ${nextFile.fileName}")

        uiScope.launch {
            withContext(Dispatchers.IO) {
                if (hashOrLink.isNotBlank()) {
                    torrServeClient.triggerPreload(hashOrLink, nextFile.id)
                }
            }
            prepareMedia(streamUrl, startPositionMs)
        }
    }

    private fun showEpisodeSwitcherDialog() {
        if (episodes.size <= 1) {
            Toast.makeText(this, "Список серий недоступен", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_episodes)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.78).toInt(),
            (resources.displayMetrics.heightPixels * 0.80).toInt()
        )

        val tvHeader = dialog.findViewById<TextView>(R.id.tvEpisodesHeader)
        val tvCount = dialog.findViewById<TextView>(R.id.tvEpisodesCount)
        val rvEpisodes = dialog.findViewById<RecyclerView>(R.id.rvEpisodes)

        tvHeader.text = title
        val curPos = if (currentEpisodeIndex >= 0) currentEpisodeIndex + 1 else 1
        tvCount.text = "Серия $curPos из ${episodes.size} • Нажмите OK для переключения"

        val epAdapter = EpisodeAdapter(
            files = episodes,
            watchedChecker = { id ->
                id == fileId || (torrentHash.isNotBlank() && storage.isEpisodeWatched(torrentHash, id))
            },
            onEpisodeClick = { selected ->
                dialog.dismiss()
                val idx = episodes.indexOfFirst { it.id == selected.id }
                if (idx >= 0 && idx != currentEpisodeIndex) {
                    switchToEpisode(idx)
                }
            }
        )

        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = epAdapter
        dialog.show()

        val focusIdx = currentEpisodeIndex.coerceIn(0, episodes.lastIndex)
        rvEpisodes.post {
            rvEpisodes.scrollToPosition(focusIdx)
            rvEpisodes.findViewHolderForAdapterPosition(focusIdx)?.itemView?.requestFocus()
                ?: rvEpisodes.getChildAt(0)?.requestFocus()
        }
    }

    private fun checkAutoNextTrigger() {
        if (!autoNextEnabled || nextCardActive || nextCardDismissedForCurrent || !hasNextEpisode()) return
        val exo = player ?: return
        if (!exo.isPlaying) return
        val dur = exo.duration
        if (dur < 180_000L) return
        val remainingMs = dur - exo.currentPosition
        if (remainingMs in 1_000L..15_000L) {
            val sec = ((remainingMs + 999L) / 1000L).toInt().coerceIn(5, 15)
            showNextEpisodeCard(countdownSec = sec)
        }
    }

    private fun showNextEpisodeCard(countdownSec: Int) {
        if (!hasNextEpisode()) return
        val nextFile = episodes[currentEpisodeIndex + 1]
        hideOsd()
        nextCardActive = true
        nextCountdownSec = countdownSec.coerceAtLeast(3)
        tvNextEpisodeName.text = nextFile.fileName
        layoutNextEpisode.visibility = View.VISIBLE
        btnNextPlayNow.requestFocus()
        mainHandler.removeCallbacks(nextCountdownRunnable)
        mainHandler.post(nextCountdownRunnable)
    }

    private fun hideNextEpisodeCard(dismissed: Boolean) {
        nextCardActive = false
        if (dismissed) {
            nextCardDismissedForCurrent = true
        }
        mainHandler.removeCallbacks(nextCountdownRunnable)
        layoutNextEpisode.visibility = View.GONE
    }

    private fun showOsd() {
        updateOsdProgress()
        updateStreamInfoText()
        maybePollTorrServeStats()
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
        mainHandler.postDelayed(osdHideRunnable, 6_500L)
    }

    private fun showQuickHudMessage(message: String) {
        tvQuickHud.text = message
        tvQuickHud.visibility = View.VISIBLE
        mainHandler.removeCallbacks(quickHudHideRunnable)
        mainHandler.postDelayed(quickHudHideRunnable, 2_000L)
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    private fun seekBy(deltaMs: Long, showQuickHud: Boolean) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        updateOsdProgress()
        if (showQuickHud) {
            val sec = kotlin.math.abs(deltaMs / 1000L)
            val stepLabel = if (sec >= 60L) "${sec / 60L} мин" else "${sec}с"
            val arrow = if (deltaMs < 0L) "◀ -$stepLabel" else "+$stepLabel ▶"
            showQuickHudMessage("$arrow   ${formatDuration(target)} / ${formatDuration(duration)}")
        }
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

    private fun updateStreamInfoText() {
        val exo = player
        val parts = mutableListOf<String>()

        val vf = exo?.videoFormat
        if (vf != null) {
            if (vf.width > 0 && vf.height > 0) {
                parts.add("${vf.width}×${vf.height}")
            }
            val vCodec = formatVideoCodec(vf.sampleMimeType, vf.codecs)
            if (vCodec.isNotBlank()) parts.add(vCodec)

            val transfer = vf.colorInfo?.colorTransfer ?: C.COLOR_TRANSFER_SDR
            if (transfer == C.COLOR_TRANSFER_ST2084) {
                parts.add("HDR10")
            } else if (transfer == C.COLOR_TRANSFER_HLG) {
                parts.add("HLG")
            }

            if (vf.frameRate > 1f) {
                parts.add(String.format(Locale.US, "%.2f fps", vf.frameRate).removeSuffix(".00 fps").let { s ->
                    if (s.endsWith("fps")) s else "$s fps"
                })
            }

            if (vf.bitrate > 0) {
                val mbps = vf.bitrate / 1_000_000.0
                if (mbps >= 0.5) {
                    parts.add(String.format(Locale.US, "%.1f Мбит/с", mbps))
                }
            }
        }

        val af = exo?.audioFormat
        if (af != null) {
            val aCodec = formatAudioCodec(af.sampleMimeType, af.codecs)
            val ch = formatChannels(af.channelCount)
            val audioText = listOf(aCodec, ch).filter { it.isNotBlank() }.joinToString(" ")
            if (audioText.isNotBlank()) parts.add(audioText)
        }

        if (torrentStatsSummary.isNotBlank()) {
            parts.add(torrentStatsSummary)
        }

        tvStreamInfo.text = parts.joinToString("  •  ")
        tvStreamInfo.visibility = if (parts.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatVideoCodec(mime: String?, codecs: String?): String {
        val m = mime?.lowercase(Locale.US).orEmpty()
        return when {
            m.contains("dolby-vision") -> "Dolby Vision"
            m.contains("hevc") || m.contains("h265") -> "HEVC"
            m.contains("avc") || m.contains("h264") -> "H.264"
            m.contains("av01") || m.contains("av1") -> "AV1"
            m.contains("vp9") -> "VP9"
            m.contains("mpeg2") -> "MPEG-2"
            else -> codecs?.substringBefore('.')?.uppercase(Locale.US).orEmpty()
        }
    }

    private fun formatAudioCodec(mime: String?, codecs: String?): String {
        val m = mime?.lowercase(Locale.US).orEmpty()
        return when {
            m.contains("eac3-joc") -> "E-AC3 Atmos"
            m.contains("eac3") -> "E-AC3"
            m.contains("ac3") -> "AC3"
            m.contains("dts.hd") -> "DTS-HD"
            m.contains("dts") -> "DTS"
            m.contains("true-hd") -> "TrueHD"
            m.contains("flac") -> "FLAC"
            m.contains("opus") -> "Opus"
            m.contains("mp4a") || m.contains("aac") -> "AAC"
            m.contains("mpeg") -> "MP3"
            else -> codecs?.substringBefore('.')?.uppercase(Locale.US).orEmpty()
        }
    }

    private fun formatChannels(count: Int): String {
        return when (count) {
            1 -> "1.0"
            2 -> "2.0"
            6 -> "5.1"
            8 -> "7.1"
            in 3..16 -> "${count}ch"
            else -> ""
        }
    }

    private data class TrackChoice(
        val label: String,
        val group: Tracks.Group?,
        val trackIndex: Int,
        val rawLang: String = "",
        val rawLabel: String = "",
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
                    val codec = formatAudioCodec(fmt.sampleMimeType, fmt.codecs)
                    val ch = formatChannels(fmt.channelCount).let { if (it.isNotBlank()) " ($it)" else "" }
                    val activeTag = if (group.isTrackSelected(i)) " ✓" else ""
                    val desc = listOfNotNull(labelName, codec.ifBlank { null }).joinToString(" • ") + ch + activeTag
                    choices.add(
                        TrackChoice(
                            label = desc,
                            group = group,
                            trackIndex = i,
                            rawLang = fmt.language.orEmpty(),
                            rawLabel = fmt.label.orEmpty()
                        )
                    )
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
                playerPrefs.preferredAudioLang = chosen.rawLang
                playerPrefs.preferredAudioLabel = chosen.rawLabel
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, chosen.trackIndex))
                )
                updateStreamInfoText()
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
                    choices.add(
                        TrackChoice(
                            label = "$labelName$activeTag",
                            group = group,
                            trackIndex = i,
                            rawLang = fmt.language.orEmpty(),
                            rawLabel = fmt.label.orEmpty()
                        )
                    )
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
                    playerPrefs.subtitlesDisabled = true
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val grp = chosen.group ?: return@setItems
                    playerPrefs.subtitlesDisabled = false
                    playerPrefs.preferredSubLang = chosen.rawLang
                    playerPrefs.preferredSubLabel = chosen.rawLabel
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, chosen.trackIndex))
                }
                trackSelector.setParameters(builder)
            }
            .show()
    }

    private fun savePlaybackProgress(completed: Boolean = false) {
        val exo = player ?: return
        val dur = exo.duration.coerceAtLeast(0L)
        val rawPos = exo.currentPosition.coerceAtLeast(0L)
        val pos = if (completed && dur > 0L) dur else rawPos
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

    private fun extractHashFromStreamOrLink(url: String, magnetLink: String): String {
        val fromQuery = runCatching { Uri.parse(url).getQueryParameter("link").orEmpty().trim() }
            .getOrDefault("")
        if (fromQuery.isNotBlank() && !fromQuery.startsWith("magnet:", ignoreCase = true) && !fromQuery.startsWith("http", ignoreCase = true)) {
            return fromQuery
        }
        val m = BTIH_REGEX.find(magnetLink)
        return m?.groupValues?.get(1)?.lowercase(Locale.US).orEmpty()
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EPISODE_LABEL = "extra_episode_label"
        const val EXTRA_LINK = "extra_link"
        const val EXTRA_FILE_ID = "extra_file_id"
        const val EXTRA_START_POS_MS = "extra_start_pos_ms"

        private val BTIH_REGEX = Regex("btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
    }
}
