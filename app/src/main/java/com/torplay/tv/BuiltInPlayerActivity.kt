package com.torplay.tv

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

@OptIn(UnstableApi::class)
class BuiltInPlayerActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var storage: StorageManager
    private lateinit var playerPrefs: PlayerPrefs
    private lateinit var torrServeClient: TorrServeClient
    private lateinit var searchEngine: SearchEngine

    private enum class TouchGestureMode { NONE, BRIGHTNESS, VOLUME, SEEK }

    private lateinit var audioManager: AudioManager
    private lateinit var playerView: PlayerView
    private lateinit var vlcVideoLayout: VLCVideoLayout
    private lateinit var viewTouchSurface: View
    private lateinit var viewOsdDismissArea: View
    private lateinit var tvQuickHud: TextView
    private lateinit var layoutOsd: FrameLayout
    private lateinit var layoutPlayerBuffering: LinearLayout
    private lateinit var tvBufferingTitle: TextView
    private lateinit var tvBufferingStats: TextView
    private lateinit var btnStartFromBeginning: TextView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvStreamInfo: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnRewind: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var btnForward: TextView
    private lateinit var btnAudioTrack: TextView
    private lateinit var btnAudioBoost: TextView
    private lateinit var btnSubtitles: TextView
    private lateinit var btnResizeMode: TextView
    private lateinit var btnEngineSwitch: TextView
    private lateinit var btnEpisodes: TextView
    private lateinit var btnNextEpisode: TextView
    private lateinit var btnAutoNext: TextView

    private lateinit var layoutNextEpisode: LinearLayout
    private lateinit var tvNextEpisodeHeader: TextView
    private lateinit var tvNextEpisodeName: TextView
    private lateinit var btnNextCancel: TextView
    private lateinit var btnNextPlayNow: TextView

    // Active engine state (Media3 or LibVLC)
    private var activeEngine: Int = StorageManager.PLAYER_ENGINE_MEDIA3
    private var hasAutoFallenBackToVlc: Boolean = false

    // Media3 state
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    // LibVLC state
    private var libVlc: LibVLC? = null
    private var vlcPlayer: VlcMediaPlayer? = null
    private var vlcViewsAttached: Boolean = false
    private var vlcForceSoftwareDecode: Boolean = false
    private var vlcRetriedSoftwareDecode: Boolean = false
    private var vlcLastKnownTimeMs: Long = 0L
    private var vlcLastKnownLengthMs: Long = 0L
    private var vlcLastProgressTimeMs: Long = -1L
    private var vlcLastProgressAtMs: Long = 0L
    private var vlcBufferPercent: Int = 0
    private var vlcLastBufferPct: Int = 0
    private var vlcLastBufferProgressAtMs: Long = 0L
    private var vlcRequestedStartTimeMs: Long = 0L
    private var vlcMediaEndedHandled: Boolean = false
    private var vlcHasStartedPlayback: Boolean = false
    private val vlcThrashTimestamps = ArrayDeque<Long>()
    private var vlcThrashLastHandledAtMs: Long = 0L
    private var lastManualSeekAtMs: Long = 0L

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
    private var audioBoostDb: Int = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var attachedAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var appliedSavedTracksForItem: Boolean = false
    private var pendingInitialSeekMs: Long = 0L
    private var pendingResumeSanityCheck: Boolean = false
    private var bufferingStartedAtMs: Long = 0L
    private var isBufferingNow: Boolean = false
    private var forceHevcFallback: Boolean = false
    private var hasRetriedCodecFallback: Boolean = false
    private var lastAdvancePosMs: Long = -1L
    private var lastAdvanceRealTimeMs: Long = 0L
    private var smoothAdvanceAccumMs: Long = 0L
    private var lastMedia3BufferedPosMs: Long = -1L
    private var lastMedia3BufferProgressAtMs: Long = 0L
    private var stallRecoveryCooldownUntilMs: Long = 0L
    private var media3StallRecoveryCount: Int = 0
    private var vlcStallRecoveryCount: Int = 0
    private var lastTorrStatsLogSummary: String = "нет данных"
    private var lastTorrActivePeers: Int = -1
    private var lastTorrTotalPeers: Int = 0
    private var lastTorrSpeedBytes: Double = 0.0
    private var lastTorrPreloadPct: Int = 0
    private var deadTorrentSinceMs: Long = 0L
    private var altSourcesDialogShownForHash: String = ""
    private var altSourcesSnoozeUntilMs: Long = 0L
    private var altSourcesDialog: Dialog? = null
    private var altSearchJob: Job? = null
    private var altSwitchJob: Job? = null
    private var initialTorrentHash: String = ""
    private var fromHistoryLaunch: Boolean = false

    private var nextCountdownSec: Int = 0
    private var nextCardDismissedForCurrent: Boolean = false
    private var nextCardActive: Boolean = false

    private var isUserSeeking: Boolean = false
    private var touchGestureMode: TouchGestureMode = TouchGestureMode.NONE
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var gestureStartBrightness: Float = 0.5f
    private var gestureStartVolume: Int = 0
    private var gestureMaxVolume: Int = 15
    private var gestureStartPosMs: Long = 0L
    private var pendingSwipeSeekMs: Long = -1L

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
            checkPlaybackStallWatchdog()
            if (layoutOsd.visibility == View.VISIBLE || isBufferingNow) {
                maybePollTorrServeStats(forceFast = isBufferingNow)
            }
            updateBufferingOverlayState()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private fun isVlcEngine(): Boolean = activeEngine == StorageManager.PLAYER_ENGINE_VLC

    private fun isCurrentlyPlaying(): Boolean {
        return if (isVlcEngine()) {
            vlcPlayer?.isPlaying == true
        } else {
            player?.isPlaying == true
        }
    }

    private fun currentPositionMs(): Long {
        return if (isVlcEngine()) {
            val t = vlcPlayer?.getTime() ?: -1L
            if (t >= 0L) {
                vlcLastKnownTimeMs = t
                t
            } else {
                vlcLastKnownTimeMs.coerceAtLeast(0L)
            }
        } else {
            player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }
    }

    private fun currentDurationMs(): Long {
        return if (isVlcEngine()) {
            val len = vlcPlayer?.getLength() ?: -1L
            if (len > 0L) {
                vlcLastKnownLengthMs = len
                len
            } else {
                vlcLastKnownLengthMs.coerceAtLeast(0L)
            }
        } else {
            player?.duration?.takeIf { it > 0L } ?: 0L
        }
    }

    private fun seekToPositionMs(targetMs: Long) {
        val safeTarget = targetMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        lastManualSeekAtMs = now
        vlcThrashTimestamps.clear()
        lastAdvancePosMs = -1L
        lastAdvanceRealTimeMs = 0L
        smoothAdvanceAccumMs = 0L
        bufferingStartedAtMs = now
        vlcLastBufferPct = 0
        vlcLastBufferProgressAtMs = now
        lastMedia3BufferedPosMs = -1L
        lastMedia3BufferProgressAtMs = now
        stallRecoveryCooldownUntilMs = now + 8_000L
        if (isVlcEngine()) {
            val vlc = vlcPlayer ?: return
            vlcRequestedStartTimeMs = 0L
            vlcLastKnownTimeMs = safeTarget
            vlcLastProgressTimeMs = -1L
            vlcLastProgressAtMs = 0L
            runCatching { vlc.setTime(safeTarget) }
        } else {
            player?.seekTo(safeTarget)
        }
    }

    private fun pausePlayback() {
        if (isVlcEngine()) {
            runCatching { vlcPlayer?.pause() }
        } else {
            player?.pause()
        }
    }

    private fun resumePlayback() {
        if (isVlcEngine()) {
            runCatching { vlcPlayer?.play() }
        } else {
            player?.play()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        storage = StorageManager(this)
        playerPrefs = PlayerPrefs(this)
        torrServeClient = TorrServeClient(storage)
        searchEngine = SearchEngine(storage)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Видео" }
        episodeLabel = intent.getStringExtra(EXTRA_EPISODE_LABEL).orEmpty()
        link = intent.getStringExtra(EXTRA_LINK).orEmpty()
        fileId = intent.getIntExtra(EXTRA_FILE_ID, 1)
        startPositionMs = intent.getLongExtra(EXTRA_START_POS_MS, 0L)
        fromHistoryLaunch = intent.getBooleanExtra(EXTRA_FROM_HISTORY, false)
        val requestedEngine = intent.getIntExtra(EXTRA_PLAYER_ENGINE, storage.playerEngine)
        activeEngine = if (requestedEngine == StorageManager.PLAYER_ENGINE_VLC) {
            StorageManager.PLAYER_ENGINE_VLC
        } else {
            StorageManager.PLAYER_ENGINE_MEDIA3
        }

        torrentHash = extractHashFromStreamOrLink(streamUrl, link)
        initialTorrentHash = torrentHash
        if (torrentHash.isNotBlank()) {
            storage.setLastEpisodeForTorrent(torrentHash, fileId)
        }

        resizeModeIndex = playerPrefs.resizeModeIndex
        autoNextEnabled = playerPrefs.autoNextEpisode
        val isContinuing = fromHistoryLaunch ||
            startPositionMs > 2_000L ||
            storage.hasWatchHistoryFor(link, torrentHash)
        audioBoostDb = if (isContinuing) {
            storage.getAudioBoostForMedia(link, torrentHash)
        } else {
            storage.setAudioBoostForMedia(link, torrentHash, 0)
            0
        }

        bindViews()
        setupControls()
        setupTouchGestures()
        updateSurfaceVisibility()
        applyResizeMode(showFeedback = false)
        updateEngineSwitchUi()
        updateAudioBoostUi()
        updateAutoNextUi()
        updateSeriesControlsUi()
        initActivePlayer(startPositionMs)
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
        pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { altSourcesDialog?.dismiss() }
        altSourcesDialog = null
        savePlaybackProgress()
        mainHandler.removeCallbacksAndMessages(null)
        uiScope.cancel()
        releaseMedia3Player()
        releaseVlcPlayer()
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
                        val wasPlaying = isCurrentlyPlaying()
                        togglePlayPause()
                        showQuickHudMessage(if (wasPlaying) "❚❚ Пауза" else "▶ Воспроизведение")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        resumePlayback()
                        showQuickHudMessage("▶ Воспроизведение")
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        pausePlayback()
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
        vlcVideoLayout = findViewById(R.id.vlcVideoLayout)
        viewTouchSurface = findViewById(R.id.viewTouchSurface)
        viewOsdDismissArea = findViewById(R.id.viewOsdDismissArea)
        tvQuickHud = findViewById(R.id.tvQuickHud)
        layoutOsd = findViewById(R.id.layoutOsd)
        layoutPlayerBuffering = findViewById(R.id.layoutPlayerBuffering)
        tvBufferingTitle = findViewById(R.id.tvBufferingTitle)
        tvBufferingStats = findViewById(R.id.tvBufferingStats)
        btnStartFromBeginning = findViewById(R.id.btnStartFromBeginning)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvStreamInfo = findViewById(R.id.tvStreamInfo)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnAudioBoost = findViewById(R.id.btnAudioBoost)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnResizeMode = findViewById(R.id.btnResizeMode)
        btnEngineSwitch = findViewById(R.id.btnEngineSwitch)
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

    private fun updateSurfaceVisibility() {
        if (isVlcEngine()) {
            playerView.player = null
            playerView.visibility = View.GONE
            vlcVideoLayout.visibility = View.VISIBLE
        } else {
            vlcVideoLayout.visibility = View.GONE
            playerView.visibility = View.VISIBLE
        }
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
            seekBy(-10_000L, showQuickHud = true)
            scheduleOsdAutoHide()
        }
        btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleOsdAutoHide()
        }
        btnForward.setOnClickListener {
            seekBy(10_000L, showQuickHud = true)
            scheduleOsdAutoHide()
        }
        btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
            scheduleOsdAutoHide()
        }
        btnAudioBoost.setOnClickListener {
            cycleAudioBoost()
            scheduleOsdAutoHide()
        }
        btnStartFromBeginning.setOnClickListener {
            pendingInitialSeekMs = 0L
            vlcRequestedStartTimeMs = 0L
            pendingResumeSanityCheck = false
            startPositionMs = 0L
            btnStartFromBeginning.visibility = View.GONE
            prepareActiveMedia(streamUrl, 0L)
            showQuickHudMessage("▶ Запуск с начала")
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
        btnEngineSwitch.setOnClickListener {
            cyclePlayerEngineMode()
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
        viewOsdDismissArea.setOnClickListener {
            hideOsd()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = currentDurationMs().takeIf { it > 0L } ?: return
                val targetMs = (dur * progress.toLong()) / 1000L
                tvCurrentTime.text = formatDuration(targetMs)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                isUserSeeking = true
                mainHandler.removeCallbacks(osdHideRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                isUserSeeking = false
                val dur = currentDurationMs().takeIf { it > 0L }
                if (dur != null && bar != null) {
                    val targetMs = ((dur * bar.progress.toLong()) / 1000L).coerceIn(0L, dur)
                    seekToPositionMs(targetMs)
                }
                updateOsdProgress()
                scheduleOsdAutoHide()
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(18f)
        val edgeGuardPx = 40f * resources.displayMetrics.density

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (nextCardActive) return false
                    if (layoutOsd.visibility == View.VISIBLE) {
                        hideOsd()
                    } else {
                        showOsd()
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (nextCardActive) return false
                    val width = viewTouchSurface.width.coerceAtLeast(1)
                    if (e.x < width / 2f) {
                        seekBy(-15_000L, showQuickHud = true)
                    } else {
                        seekBy(30_000L, showQuickHud = true)
                    }
                    return true
                }
            }
        )

        viewTouchSurface.setOnTouchListener { view, event ->
            if (nextCardActive) return@setOnTouchListener false

            val width = view.width.coerceAtLeast(1).toFloat()
            val height = view.height.coerceAtLeast(1).toFloat()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchGestureMode = TouchGestureMode.NONE
                    pendingSwipeSeekMs = -1L
                    gestureStartBrightness = currentScreenBrightness()
                    gestureMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestureStartPosMs = currentPositionMs()
                    gestureDetector.onTouchEvent(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY

                    if (touchGestureMode == TouchGestureMode.NONE) {
                        val outsideVerticalEdges = touchStartY > edgeGuardPx && touchStartY < (height - edgeGuardPx)
                        if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                            if (currentDurationMs() > 0L) {
                                touchGestureMode = TouchGestureMode.SEEK
                            }
                        } else if (outsideVerticalEdges &&
                            kotlin.math.abs(dy) > touchSlop &&
                            kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f
                        ) {
                            touchGestureMode = if (touchStartX < width / 2f) {
                                TouchGestureMode.BRIGHTNESS
                            } else {
                                TouchGestureMode.VOLUME
                            }
                        }
                    }

                    when (touchGestureMode) {
                        TouchGestureMode.NONE -> {
                            gestureDetector.onTouchEvent(event)
                        }
                        TouchGestureMode.BRIGHTNESS -> {
                            val delta = -dy / (height * 0.65f)
                            val newBright = (gestureStartBrightness + delta).coerceIn(0.02f, 1.0f)
                            val lp = window.attributes
                            lp.screenBrightness = newBright
                            window.attributes = lp
                            val pct = (newBright * 100f).toInt().coerceIn(1, 100)
                            showQuickHudPersistent("☀ Яркость: $pct%")
                        }
                        TouchGestureMode.VOLUME -> {
                            val deltaSteps = (-dy / (height * 0.65f)) * gestureMaxVolume
                            val targetVol = kotlin.math.round(gestureStartVolume + deltaSteps)
                                .toInt()
                                .coerceIn(0, gestureMaxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val pct = (targetVol * 100) / gestureMaxVolume
                            showQuickHudPersistent("🔊 Громкость: $pct%")
                        }
                        TouchGestureMode.SEEK -> {
                            val dur = currentDurationMs()
                            if (dur > 0L) {
                                val deltaMs = ((dx / width) * 180_000f).toLong()
                                val target = (gestureStartPosMs + deltaMs).coerceIn(0L, dur)
                                pendingSwipeSeekMs = target
                                val absSec = kotlin.math.abs(deltaMs / 1000L)
                                val deltaText = if (absSec >= 60L) {
                                    "${absSec / 60L}м ${absSec % 60L}с"
                                } else {
                                    "${absSec}с"
                                }
                                val dir = if (deltaMs < 0L) "◀ -$deltaText" else "+$deltaText ▶"
                                showQuickHudPersistent("$dir   ${formatDuration(target)} / ${formatDuration(dur)}")
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (touchGestureMode == TouchGestureMode.NONE) {
                        gestureDetector.onTouchEvent(event)
                    } else {
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            touchGestureMode == TouchGestureMode.SEEK &&
                            pendingSwipeSeekMs >= 0L
                        ) {
                            seekToPositionMs(pendingSwipeSeekMs)
                            updateOsdProgress()
                        }
                        touchGestureMode = TouchGestureMode.NONE
                        pendingSwipeSeekMs = -1L
                        mainHandler.removeCallbacks(quickHudHideRunnable)
                        mainHandler.postDelayed(quickHudHideRunnable, 1_400L)
                    }
                    true
                }

                else -> gestureDetector.onTouchEvent(event)
            }
        }
    }

    private fun currentScreenBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) return current.coerceIn(0.02f, 1.0f)
        val sys = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (sys / 255f).coerceIn(0.05f, 1.0f)
    }

    private fun showQuickHudPersistent(message: String) {
        tvQuickHud.text = message
        tvQuickHud.visibility = View.VISIBLE
        mainHandler.removeCallbacks(quickHudHideRunnable)
    }

    private fun applyResizeMode(showFeedback: Boolean) {
        val exoMode = when (resizeModeIndex) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val vlcScale = when (resizeModeIndex) {
            1 -> VlcMediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            2 -> VlcMediaPlayer.ScaleType.SURFACE_FILL
            3 -> VlcMediaPlayer.ScaleType.SURFACE_16_9
            else -> VlcMediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        val labelRes = when (resizeModeIndex) {
            1 -> R.string.player_resize_zoom
            2 -> R.string.player_resize_fill
            3 -> R.string.player_resize_width
            else -> R.string.player_resize_fit
        }
        playerView.resizeMode = exoMode
        runCatching { vlcPlayer?.setVideoScale(vlcScale) }
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

    private fun boostDbToVlcVolumePercent(db: Int): Int {
        return when (db) {
            3 -> 125
            6 -> 150
            9 -> 175
            12 -> 200
            else -> 100
        }
    }

    private fun cycleAudioBoost() {
        val nextIdx = (BOOST_STEPS_DB.indexOf(audioBoostDb) + 1) % BOOST_STEPS_DB.size
        audioBoostDb = BOOST_STEPS_DB[nextIdx]
        storage.setAudioBoostForMedia(link, torrentHash, audioBoostDb)
        updateAudioBoostUi()
        if (isVlcEngine()) {
            applyVlcAudioBoost()
            val pct = boostDbToVlcVolumePercent(audioBoostDb)
            val msg = if (audioBoostDb > 0) {
                "🔊 Усиление звука: +$audioBoostDb дБ ($pct%)"
            } else {
                "🔊 Усиление звука: Выкл (100%)"
            }
            showQuickHudMessage(msg)
        } else {
            applyAudioBoostToSession(player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
            val msg = if (audioBoostDb > 0) {
                "🔊 Усиление звука: +$audioBoostDb дБ"
            } else {
                "🔊 Усиление звука: Выкл"
            }
            showQuickHudMessage(msg)
        }
    }

    private fun updateAudioBoostUi() {
        btnAudioBoost.isSelected = audioBoostDb > 0
        btnAudioBoost.text = if (audioBoostDb > 0) {
            "Буст +$audioBoostDb дБ"
        } else {
            getString(R.string.player_boost_off)
        }
    }

    private fun applyVlcAudioBoost() {
        val vlc = vlcPlayer ?: return
        val volPct = boostDbToVlcVolumePercent(audioBoostDb)
        runCatching { vlc.setVolume(volPct) }.onFailure { err ->
            AppLogger.w("Player", "VLC setVolume($volPct) ошибка: ${err.message}")
        }
    }

    private fun applyAudioBoostToSession(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        if (audioBoostDb <= 0) {
            runCatching {
                loudnessEnhancer?.setTargetGain(0)
                loudnessEnhancer?.enabled = false
            }
            return
        }
        runCatching {
            if (loudnessEnhancer == null || attachedAudioSessionId != sessionId) {
                releaseLoudnessEnhancer()
                loudnessEnhancer = LoudnessEnhancer(sessionId)
                attachedAudioSessionId = sessionId
            }
            loudnessEnhancer?.setTargetGain(audioBoostDb * 100)
            loudnessEnhancer?.enabled = true
        }.onFailure { err ->
            AppLogger.w("Player", "LoudnessEnhancer недоступен: ${err.message}")
        }
    }

    private fun releaseLoudnessEnhancer() {
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        attachedAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun updateSeriesControlsUi() {
        val multi = episodes.size > 1
        val hasNext = multi && hasNextEpisode()

        btnEpisodes.visibility = if (multi) View.VISIBLE else View.GONE
        btnNextEpisode.visibility = if (hasNext) View.VISIBLE else View.GONE
        btnAutoNext.visibility = if (multi) View.VISIBLE else View.GONE

        if (multi) {
            val posLabel = if (currentEpisodeIndex >= 0) "${currentEpisodeIndex + 1}/${episodes.size}" else "${episodes.size}"
            btnEpisodes.text = "${getString(R.string.player_episodes_btn)} ($posLabel)"
        }

        // Dynamic single-row left/right focus routing
        val rowButtons = mutableListOf<TextView>(btnAudioTrack, btnAudioBoost, btnSubtitles, btnResizeMode, btnEngineSwitch)
        if (multi) rowButtons.add(btnEpisodes)
        if (hasNext) rowButtons.add(btnNextEpisode)
        if (multi) rowButtons.add(btnAutoNext)

        for (i in rowButtons.indices) {
            val btn = rowButtons[i]
            btn.nextFocusLeftId = if (i > 0) rowButtons[i - 1].id else btn.id
            btn.nextFocusRightId = if (i < rowButtons.lastIndex) rowButtons[i + 1].id else btn.id
            btn.nextFocusUpId = R.id.btnPlayPause
            btn.nextFocusDownId = btn.id
        }

        btnRewind.nextFocusDownId = R.id.btnAudioTrack
        btnPlayPause.nextFocusDownId = R.id.btnAudioBoost
        btnForward.nextFocusDownId = R.id.btnSubtitles

        updateHeaderTitle()
    }

    private fun initActivePlayer(resumePositionMs: Long) {
        updateSurfaceVisibility()
        if (isVlcEngine()) {
            initVlcPlayer(resumePositionMs)
        } else {
            initMedia3Player(resumePositionMs)
        }
    }

    private fun prepareActiveMedia(url: String, seekPosMs: Long) {
        if (isVlcEngine()) {
            prepareVlcMedia(url, seekPosMs)
        } else {
            prepareMedia3(url, seekPosMs)
        }
    }

    private fun releaseMedia3Player() {
        releaseLoudnessEnhancer()
        runCatching { playerView.player = null }
        runCatching { player?.release() }
        player = null
        trackSelector = null
    }

    private fun releaseVlcPlayer() {
        val vlc = vlcPlayer
        vlcPlayer = null
        if (vlc != null) {
            runCatching { vlc.setEventListener(null) }
            runCatching { vlc.stop() }
            if (vlcViewsAttached) {
                runCatching { vlc.detachViews() }
                vlcViewsAttached = false
            }
            runCatching { vlc.release() }
        }
        val lib = libVlc
        libVlc = null
        if (lib != null) {
            runCatching { lib.release() }
        }
    }

    private fun updateEngineSwitchUi() {
        val labelRes = when {
            !isVlcEngine() -> R.string.player_engine_switch_media3
            vlcForceSoftwareDecode -> R.string.player_engine_switch_vlc_sw
            else -> R.string.player_engine_switch_vlc_hw
        }
        btnEngineSwitch.text = getString(labelRes)
        btnEngineSwitch.isSelected = isVlcEngine() && vlcForceSoftwareDecode
    }

    private fun cyclePlayerEngineMode() {
        if (isFinishing || isDestroyed) return
        val resumeAt = currentPositionMs()
            .coerceAtLeast(vlcRequestedStartTimeMs)
            .coerceAtLeast(pendingInitialSeekMs)
        savePlaybackProgress()
        vlcThrashTimestamps.clear()
        media3StallRecoveryCount = 0
        vlcStallRecoveryCount = 0
        stallRecoveryCooldownUntilMs = System.currentTimeMillis() + 8_000L

        when {
            !isVlcEngine() -> {
                // Media3 -> VLC HW
                AppLogger.i("Player", "Ручное переключение движка: Media3 -> VLC (HW) с ${resumeAt}мс")
                releaseMedia3Player()
                activeEngine = StorageManager.PLAYER_ENGINE_VLC
                vlcForceSoftwareDecode = false
                vlcRetriedSoftwareDecode = false
                updateSurfaceVisibility()
                updateEngineSwitchUi()
                initVlcPlayer(resumeAt)
                showQuickHudMessage("⚙ Движок: VLC (HW)")
            }
            !vlcForceSoftwareDecode -> {
                // VLC HW -> VLC SW
                AppLogger.i("Player", "Ручное переключение движка: VLC (HW) -> VLC (SW) с ${resumeAt}мс")
                vlcForceSoftwareDecode = true
                vlcRetriedSoftwareDecode = true
                updateEngineSwitchUi()
                prepareVlcMedia(streamUrl, resumeAt)
                showQuickHudMessage("⚙ Движок: VLC (SW)")
            }
            else -> {
                // VLC SW -> Media3
                AppLogger.i("Player", "Ручное переключение движка: VLC (SW) -> Media3 с ${resumeAt}мс")
                releaseVlcPlayer()
                activeEngine = StorageManager.PLAYER_ENGINE_MEDIA3
                vlcForceSoftwareDecode = false
                hasAutoFallenBackToVlc = false
                hasRetriedCodecFallback = false
                forceHevcFallback = false
                updateSurfaceVisibility()
                updateEngineSwitchUi()
                initMedia3Player(resumeAt)
                showQuickHudMessage("⚙ Движок: Media3")
            }
        }
    }

    private fun switchToVlcEngine(resumeAtMs: Long) {
        if (isFinishing || isDestroyed) return
        AppLogger.w("Player", "Автопереключение Media3 -> LibVLC с позиции ${resumeAtMs}мс ($title)")
        releaseMedia3Player()
        activeEngine = StorageManager.PLAYER_ENGINE_VLC
        vlcForceSoftwareDecode = false
        vlcRetriedSoftwareDecode = false
        updateSurfaceVisibility()
        updateEngineSwitchUi()
        initVlcPlayer(resumeAtMs)
        showQuickHudMessage("⚡ Подхвачено встроенным VLC")
    }

    private fun buildSmartCodecSelector(preferHevcForDv: Boolean): MediaCodecSelector {
        return object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): MutableList<MediaCodecInfo> {
                if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                    val hevcDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        MimeTypes.VIDEO_H265,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                    if (preferHevcForDv && hevcDecoders.isNotEmpty()) {
                        return hevcDecoders.toMutableList()
                    }
                    val dvDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                    val combined = ArrayList<MediaCodecInfo>(dvDecoders.size + hevcDecoders.size)
                    combined.addAll(dvDecoders)
                    for (hevc in hevcDecoders) {
                        if (combined.none { it.name == hevc.name }) {
                            combined.add(hevc)
                        }
                    }
                    return combined
                }
                return MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                ).toMutableList()
            }
        }
    }

    private fun initMedia3Player(resumePositionMs: Long = startPositionMs) {
        val prefAudio = playerPrefs.preferredAudioLang.ifBlank { "rus" }
        val prefSub = playerPrefs.preferredSubLang.ifBlank { "rus" }
        val selector = DefaultTrackSelector(this).apply {
            val builder = buildUponParameters()
                .setPreferredAudioLanguage(prefAudio)
                .setPreferredTextLanguage(prefSub)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
            if (playerPrefs.subtitlesDisabled) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
            setParameters(builder)
        }
        trackSelector = selector

        val extensionMode = if (forceHevcFallback) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        }
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(buildSmartCodecSelector(preferHevcForDv = forceHevcFallback))

        val loadControlBuilder = DefaultLoadControl.Builder()
        when (storage.bufferProfile) {
            StorageManager.BUFFER_PROFILE_FAST -> {
                loadControlBuilder.setBufferDurationsMs(5_000, 12_000, 1_000, 2_000)
            }
            StorageManager.BUFFER_PROFILE_4K -> {
                loadControlBuilder
                    .setBufferDurationsMs(15_000, 35_000, 3_500, 6_000)
                    .setTargetBufferBytes(64 * 1024 * 1024)
            }
            else -> {
                loadControlBuilder.setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
            }
        }
        val loadControl = loadControlBuilder.build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkHelper.buildDirectStreamClient())
            .setUserAgent("TorPlay-TV")

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(selector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        player = exo
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isVlcEngine()) {
                    btnPlayPause.text = if (isPlaying) "❚❚" else "▶"
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (!isVlcEngine()) {
                    applyAudioBoostToSession(audioSessionId)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isVlcEngine()) return
                AppLogger.e("Player", "Ошибка Media3 ($title): ${error.errorCodeName}", error)
                val isCodecOrFormatError =
                    error.errorCode in 3000..5999 ||
                        error.errorCodeName.contains("DECOD", ignoreCase = true) ||
                        error.errorCodeName.contains("FORMAT", ignoreCase = true) ||
                        error.errorCodeName.contains("PARSING", ignoreCase = true) ||
                        error.errorCodeName.contains("AUDIO_TRACK", ignoreCase = true)

                val resumeAt = exo.currentPosition
                    .coerceAtLeast(pendingInitialSeekMs)
                    .coerceAtLeast(resumePositionMs)

                if (isCodecOrFormatError && !hasAutoFallenBackToVlc) {
                    hasAutoFallenBackToVlc = true
                    Toast.makeText(
                        this@BuiltInPlayerActivity,
                        "Ошибка кодека Media3 — автоподхват во встроенном VLC…",
                        Toast.LENGTH_SHORT
                    ).show()
                    mainHandler.post {
                        switchToVlcEngine(resumeAt)
                    }
                    return
                }

                if (isCodecOrFormatError && !hasRetriedCodecFallback) {
                    hasRetriedCodecFallback = true
                    forceHevcFallback = true
                    AppLogger.w("Player", "Повторный запуск Media3 с резервным декодером HEVC (pos=$resumeAt)")
                    Toast.makeText(
                        this@BuiltInPlayerActivity,
                        "Переключение на резервный декодер…",
                        Toast.LENGTH_SHORT
                    ).show()
                    releaseMedia3Player()
                    initMedia3Player(resumePositionMs = resumeAt)
                    return
                }

                if (isCodecOrFormatError && !isFinishing && !isDestroyed) {
                    showCodecFallbackDialog(error.errorCodeName)
                } else {
                    Toast.makeText(
                        this@BuiltInPlayerActivity,
                        "Ошибка плеера: ${error.errorCodeName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (isVlcEngine()) return
                if (!appliedSavedTracksForItem) {
                    applyRememberedTracks(tracks)
                }
                updateStreamInfoText()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isVlcEngine()) return
                if (playbackState == Player.STATE_BUFFERING) {
                    if (!isBufferingNow) {
                        isBufferingNow = true
                        bufferingStartedAtMs = System.currentTimeMillis()
                    }
                    maybePollTorrServeStats(forceFast = true)
                    updateBufferingOverlayState()
                } else if (playbackState == Player.STATE_READY) {
                    isBufferingNow = false
                    bufferingStartedAtMs = 0L
                    layoutPlayerBuffering.visibility = View.GONE
                    btnStartFromBeginning.visibility = View.GONE

                    applyAudioBoostToSession(exo.audioSessionId)

                    if (pendingInitialSeekMs > 2_000L) {
                        val targetSeek = pendingInitialSeekMs
                        pendingInitialSeekMs = 0L
                        val dur = exo.duration
                        if (dur <= 0L || targetSeek < dur - 8_000L) {
                            pendingResumeSanityCheck = true
                            exo.seekTo(targetSeek)
                        }
                    } else if (pendingResumeSanityCheck) {
                        pendingResumeSanityCheck = false
                        val dur = exo.duration
                        if (dur > 15_000L && exo.currentPosition >= dur - 8_000L) {
                            exo.seekTo(0L)
                        }
                    }
                    updateStreamInfoText()
                } else if (playbackState == Player.STATE_ENDED) {
                    isBufferingNow = false
                    layoutPlayerBuffering.visibility = View.GONE
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

        prepareMedia3(streamUrl, resumePositionMs)
    }

    private fun vlcNetworkCachingMs(): Int {
        return when (storage.bufferProfile) {
            StorageManager.BUFFER_PROFILE_FAST -> 2_000
            StorageManager.BUFFER_PROFILE_4K -> 7_000
            else -> 4_000
        }
    }

    private fun initVlcPlayer(resumePositionMs: Long = startPositionMs) {
        releaseVlcPlayer()
        val cacheMs = vlcNetworkCachingMs()
        val options = ArrayList<String>().apply {
            add("--network-caching=$cacheMs")
            add("--file-caching=$cacheMs")
            add("--live-caching=$cacheMs")
            add("--http-reconnect")
            add("--audio-time-stretch")
            add("--drop-late-frames")
            add("--skip-frames")
        }

        val vlcInstance = runCatching { LibVLC(this, options) }.getOrElse { err ->
            AppLogger.e("Player", "Не удалось инициализировать LibVLC: ${err.message}", err)
            if (!isFinishing && !isDestroyed) {
                showCodecFallbackDialog("LIBVLC_INIT")
            }
            return
        }

        val mp = VlcMediaPlayer(vlcInstance)
        libVlc = vlcInstance
        vlcPlayer = mp

        runCatching {
            mp.attachViews(vlcVideoLayout, null, true, false)
            vlcViewsAttached = true
        }.onFailure { err ->
            AppLogger.e("Player", "VLC attachViews ошибка: ${err.message}", err)
        }

        applyResizeMode(showFeedback = false)
        applyVlcAudioBoost()

        mp.setEventListener { event ->
            if (!isVlcEngine() || vlcPlayer !== mp) return@setEventListener
            when (event.type) {
                VlcMediaPlayer.Event.Opening -> {
                    vlcMediaEndedHandled = false
                    if (!isBufferingNow) {
                        isBufferingNow = true
                        bufferingStartedAtMs = System.currentTimeMillis()
                    }
                    maybePollTorrServeStats(forceFast = true)
                    updateBufferingOverlayState()
                }

                VlcMediaPlayer.Event.Buffering -> {
                    val now = System.currentTimeMillis()
                    val raw = event.buffering
                    val pct = kotlin.math.round(raw).toInt().coerceIn(0, 100)
                    vlcBufferPercent = pct
                    if (raw >= 99.0f || pct >= 100) {
                        vlcBufferPercent = 100
                        vlcLastBufferPct = 100
                        vlcLastBufferProgressAtMs = now
                        clearBufferingOverlay()
                    } else {
                        if (!isBufferingNow) {
                            isBufferingNow = true
                            bufferingStartedAtMs = now
                            vlcLastBufferPct = pct
                            vlcLastBufferProgressAtMs = now
                            recordVlcBufferingReentry(now)
                        } else if (pct > vlcLastBufferPct) {
                            vlcLastBufferPct = pct
                            vlcLastBufferProgressAtMs = now
                        }
                        maybePollTorrServeStats(forceFast = true)
                        updateBufferingOverlayState()
                    }
                }

                VlcMediaPlayer.Event.Playing -> {
                    vlcHasStartedPlayback = true
                    deadTorrentSinceMs = 0L
                    clearBufferingOverlay()
                    btnPlayPause.text = "❚❚"

                    applyVlcAudioBoost()
                    applyResizeMode(showFeedback = false)
                    maybePerformPendingVlcInitialSeek()
                    if (!appliedSavedTracksForItem) {
                        applyVlcRememberedTracks()
                    }
                    updateStreamInfoText()
                }

                VlcMediaPlayer.Event.Paused -> {
                    btnPlayPause.text = "▶"
                    clearBufferingOverlay()
                }

                VlcMediaPlayer.Event.TimeChanged -> {
                    val t = event.timeChanged
                    if (t >= 0L) {
                        if (vlcRequestedStartTimeMs > 5_000L) {
                            if (t in 1L..2_500L) {
                                // Container ignored :start-time — perform single fallback seek
                                val target = vlcRequestedStartTimeMs
                                vlcRequestedStartTimeMs = 0L
                                val dur = currentDurationMs()
                                if (dur <= 0L || target < dur - 8_000L) {
                                    pendingResumeSanityCheck = true
                                    vlcLastKnownTimeMs = target
                                    bufferingStartedAtMs = System.currentTimeMillis()
                                    vlcLastBufferPct = 0
                                    vlcLastBufferProgressAtMs = bufferingStartedAtMs
                                    runCatching { mp.setTime(target) }
                                    return@setEventListener
                                }
                            } else if (t >= 3_000L) {
                                vlcRequestedStartTimeMs = 0L
                            }
                        }
                        val advanced = vlcLastProgressTimeMs >= 0L && t != vlcLastProgressTimeMs
                        vlcLastKnownTimeMs = t
                        if (advanced) {
                            vlcLastProgressTimeMs = t
                            vlcLastProgressAtMs = System.currentTimeMillis()
                            vlcBufferPercent = 100
                            vlcLastBufferPct = 100
                            if (isBufferingNow) {
                                clearBufferingOverlay()
                            }
                        } else if (vlcLastProgressTimeMs < 0L) {
                            vlcLastProgressTimeMs = t
                        }
                        if (pendingResumeSanityCheck) {
                            val dur = currentDurationMs()
                            if (dur > 15_000L && t >= dur - 8_000L) {
                                pendingResumeSanityCheck = false
                                runCatching { mp.setTime(0L) }
                                vlcLastKnownTimeMs = 0L
                            } else if (t > 0L) {
                                pendingResumeSanityCheck = false
                            }
                        }
                    }
                }

                VlcMediaPlayer.Event.LengthChanged -> {
                    val len = event.lengthChanged
                    if (len > 0L) {
                        vlcLastKnownLengthMs = len
                        maybePerformPendingVlcInitialSeek()
                    }
                }

                VlcMediaPlayer.Event.ESAdded,
                VlcMediaPlayer.Event.ESSelected,
                VlcMediaPlayer.Event.Vout -> {
                    if (!appliedSavedTracksForItem) {
                        applyVlcRememberedTracks()
                    }
                    updateStreamInfoText()
                }

                VlcMediaPlayer.Event.EndReached -> {
                    if (vlcMediaEndedHandled) return@setEventListener
                    vlcMediaEndedHandled = true
                    isBufferingNow = false
                    layoutPlayerBuffering.visibility = View.GONE
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
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

                VlcMediaPlayer.Event.EncounteredError -> {
                    AppLogger.e("Player", "Ошибка LibVLC ($title), sw=$vlcForceSoftwareDecode")
                    val resumeAt = currentPositionMs()
                        .coerceAtLeast(pendingInitialSeekMs)
                        .coerceAtLeast(resumePositionMs)
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        if (!vlcRetriedSoftwareDecode) {
                            vlcRetriedSoftwareDecode = true
                            vlcForceSoftwareDecode = true
                            updateEngineSwitchUi()
                            AppLogger.w("Player", "Повторный запуск LibVLC в программном режиме (pos=$resumeAt)")
                            Toast.makeText(
                                this@BuiltInPlayerActivity,
                                "VLC: переход на программное декодирование…",
                                Toast.LENGTH_SHORT
                            ).show()
                            prepareVlcMedia(streamUrl, resumeAt)
                        } else {
                            showCodecFallbackDialog("VLC_PLAYBACK_ERROR")
                        }
                    }
                }
            }
        }

        prepareVlcMedia(streamUrl, resumePositionMs)
    }

    private fun maybePerformPendingVlcInitialSeek() {
        val vlc = vlcPlayer ?: return
        if (pendingInitialSeekMs <= 2_000L) return
        val targetSeek = pendingInitialSeekMs
        val dur = currentDurationMs()
        if (dur > 0L && targetSeek >= dur - 8_000L) {
            pendingInitialSeekMs = 0L
            return
        }
        pendingInitialSeekMs = 0L
        pendingResumeSanityCheck = true
        vlcLastKnownTimeMs = targetSeek
        runCatching { vlc.setTime(targetSeek) }
    }

    private fun prepareVlcMedia(url: String, seekPosMs: Long) {
        val lib = libVlc ?: return
        val vlc = vlcPlayer ?: return

        val now = System.currentTimeMillis()
        appliedSavedTracksForItem = false
        nextCardDismissedForCurrent = false
        vlcMediaEndedHandled = false
        vlcHasStartedPlayback = false
        vlcThrashTimestamps.clear()
        vlcLastKnownTimeMs = seekPosMs.coerceAtLeast(0L)
        vlcLastKnownLengthMs = 0L
        vlcLastProgressTimeMs = -1L
        vlcLastProgressAtMs = 0L
        vlcBufferPercent = 0
        vlcLastBufferPct = 0
        vlcLastBufferProgressAtMs = now
        hideNextEpisodeCard(dismissed = false)

        // Use native :start-time to avoid double buffering from 00:00
        vlcRequestedStartTimeMs = if (seekPosMs > 2_000L) seekPosMs else 0L
        pendingInitialSeekMs = 0L
        pendingResumeSanityCheck = seekPosMs > 2_000L
        isBufferingNow = true
        bufferingStartedAtMs = now
        updateBufferingOverlayState()

        lastAdvancePosMs = -1L
        lastAdvanceRealTimeMs = 0L
        smoothAdvanceAccumMs = 0L

        runCatching { vlc.stop() }

        val cacheMs = vlcNetworkCachingMs()
        val media = Media(lib, Uri.parse(url))
        if (vlcForceSoftwareDecode) {
            media.setHWDecoderEnabled(false, false)
        } else {
            media.setHWDecoderEnabled(true, false)
        }
        media.addOption(":network-caching=$cacheMs")
        media.addOption(":file-caching=$cacheMs")
        media.addOption(":http-reconnect")
        media.addOption(":drop-late-frames")
        media.addOption(":skip-frames")
        if (seekPosMs > 2_000L) {
            val startSec = seekPosMs / 1000L
            media.addOption(":start-time=$startSec")
        }
        if (playerPrefs.subtitlesDisabled) {
            media.addOption(":no-spu")
        }

        AppLogger.i("Player", "VLC старт: pos=${seekPosMs}мс, cache=${cacheMs}мс, sw=$vlcForceSoftwareDecode ($title)")
        vlc.setMedia(media)
        media.release()
        vlc.play()
        applyVlcAudioBoost()
        updateStreamInfoText()
    }

    private fun showCodecFallbackDialog(errorCodeName: String) {
        val fullTitle = if (episodeLabel.isNotBlank()) "$title — $episodeLabel" else title
        AlertDialog.Builder(this)
            .setTitle("Ошибка кодека ($errorCodeName)")
            .setMessage(
                "Не удалось декодировать этот видеопоток встроенным плеером.\n\n" +
                    "Открыть этот же поток во внешнем плеере (MX Player / VLC)?"
            )
            .setPositiveButton("Внешний плеер") { _, _ ->
                val res = PlayerController(this).playExternal(streamUrl, fullTitle)
                if (!res.launched) {
                    Toast.makeText(this, res.error ?: "Не удалось открыть внешний плеер", Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .setNegativeButton("Закрыть") { _, _ ->
                finish()
            }
            .show()
    }

    private fun prepareMedia3(url: String, seekPosMs: Long) {
        val exo = player ?: return
        val selector = trackSelector ?: return
        appliedSavedTracksForItem = false
        nextCardDismissedForCurrent = false
        hideNextEpisodeCard(dismissed = false)

        // Reset overrides from previous episode while preserving user preferences
        val resetBuilder = selector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(playerPrefs.preferredAudioLang.ifBlank { "rus" })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, playerPrefs.subtitlesDisabled)
        if (!playerPrefs.subtitlesDisabled && playerPrefs.preferredSubLang.isNotBlank()) {
            resetBuilder.setPreferredTextLanguage(playerPrefs.preferredSubLang)
        }
        selector.setParameters(resetBuilder)

        val mediaItem = MediaItem.fromUri(url)
        pendingInitialSeekMs = if (seekPosMs > 2_000L) seekPosMs else 0L
        pendingResumeSanityCheck = false
        isBufferingNow = true
        bufferingStartedAtMs = System.currentTimeMillis()
        updateBufferingOverlayState()

        val now = System.currentTimeMillis()
        lastAdvancePosMs = -1L
        lastAdvanceRealTimeMs = 0L
        smoothAdvanceAccumMs = 0L
        lastMedia3BufferedPosMs = -1L
        lastMedia3BufferProgressAtMs = now

        AppLogger.i("Player", "Media3 старт: pos=${seekPosMs}мс ($title)")
        exo.stop()
        exo.setMediaItem(mediaItem)
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

    private fun maybePollTorrServeStats(forceFast: Boolean = false) {
        if (torrentHash.isBlank()) return
        val now = System.currentTimeMillis()
        val interval = if (forceFast) 1_400L else 2_500L
        if (now - lastTorrStatsFetchMs < interval) return
        if (statsJob?.isActive == true) return
        lastTorrStatsFetchMs = now
        statsJob = uiScope.launch {
            val info = runCatching { torrServeClient.getTorrentInfo(torrentHash) }.getOrNull() ?: return@launch
            val pct = info.preloadPercent()
            val speed = if (info.downloadSpeed > 0.0) info.formattedSpeed() else "0 МБ/с"
            lastTorrActivePeers = info.activePeers
            lastTorrTotalPeers = info.totalPeers
            lastTorrSpeedBytes = info.downloadSpeed
            lastTorrPreloadPct = pct
            lastTorrStatsLogSummary = "preload=$pct%, speed=$speed, peers=${info.activePeers}/${info.totalPeers}"

            val nowPoll = System.currentTimeMillis()
            val recentlyPlayed = lastAdvanceRealTimeMs > 0L && (nowPoll - lastAdvanceRealTimeMs) < 10_000L
            if ((isBufferingNow || !recentlyPlayed) &&
                info.activePeers == 0 &&
                info.downloadSpeed < 10_000.0 &&
                pct < 5
            ) {
                if (deadTorrentSinceMs == 0L) {
                    deadTorrentSinceMs = nowPoll
                }
            } else if (info.activePeers > 0 || info.downloadSpeed >= 10_000.0 || pct >= 15) {
                deadTorrentSinceMs = 0L
            }

            updateTorrentStatsSummary(info)
            updateStreamInfoText()
            if (isBufferingNow) {
                val speedUi = if (info.downloadSpeed > 0.0) info.formattedSpeed() else "поиск пиров"
                val engineExtra = if (isVlcEngine()) {
                    if (vlcBufferPercent in 1..99) "VLC $vlcBufferPercent%" else "VLC"
                } else {
                    val exo = player
                    val aheadSec = if (exo != null) {
                        ((exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L) / 1000L)
                    } else 0L
                    if (aheadSec > 0L) "Media3 (+${aheadSec}с)" else "Media3"
                }
                val detail = "$engineExtra • TorrServe: $pct% • $speedUi • Пиры ${info.activePeers}/${info.totalPeers}"
                tvBufferingStats.text = detail
            }
        }
    }

    private fun recordVlcBufferingReentry(now: Long) {
        if (!vlcHasStartedPlayback) return
        if (now - lastManualSeekAtMs < 6_000L) return
        vlcThrashTimestamps.addLast(now)
        while (vlcThrashTimestamps.isNotEmpty() && now - vlcThrashTimestamps.first() > 35_000L) {
            vlcThrashTimestamps.removeFirst()
        }
        if (vlcThrashTimestamps.size >= 4 && now - vlcThrashLastHandledAtMs >= 30_000L) {
            val count = vlcThrashTimestamps.size
            vlcThrashTimestamps.clear()
            vlcThrashLastHandledAtMs = now
            val resumeAt = currentPositionMs()
            AppLogger.w(
                "Player",
                "VLC качели буферизации ($count срывов за 35с, buf=${vlcBufferPercent}%, sw=$vlcForceSoftwareDecode, $lastTorrStatsLogSummary)"
            )
            if (!vlcForceSoftwareDecode) {
                vlcForceSoftwareDecode = true
                vlcRetriedSoftwareDecode = true
                updateEngineSwitchUi()
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    Toast.makeText(
                        this,
                        "4K/VLC: частая буферизация — переключение декодера…",
                        Toast.LENGTH_SHORT
                    ).show()
                    prepareVlcMedia(streamUrl, resumeAt)
                }
            } else {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    showQuickHudMessage("⚠ Нестабильный поток — попробуйте кнопку «Движок»")
                }
            }
        }
    }

    private fun checkPlaybackStallWatchdog() {
        if (isFinishing || isDestroyed || isUserSeeking || nextCardActive || altSourcesDialog?.isShowing == true) return
        val now = System.currentTimeMillis()
        val pos = currentPositionMs()
        val playing = isCurrentlyPlaying()

        // Track genuine smooth playback advance (ignore manual seek jumps > 4s)
        if (playing && pos > 0L) {
            val delta = if (lastAdvancePosMs >= 0L) pos - lastAdvancePosMs else 0L
            if (delta in 200L..4_000L) {
                smoothAdvanceAccumMs += delta
                lastAdvancePosMs = pos
                lastAdvanceRealTimeMs = now
                bufferingStartedAtMs = 0L
                if (smoothAdvanceAccumMs >= 6_000L) {
                    media3StallRecoveryCount = 0
                    vlcStallRecoveryCount = 0
                    smoothAdvanceAccumMs = 0L
                }
            } else if (lastAdvancePosMs < 0L || kotlin.math.abs(delta) > 4_000L) {
                lastAdvancePosMs = pos
                smoothAdvanceAccumMs = 0L
            }
        }

        // Check dead torrent (no seeds / 0 speed / 0 active peers) BEFORE useless reconnect loops
        val resumeSnapshot = pos
            .coerceAtLeast(vlcRequestedStartTimeMs)
            .coerceAtLeast(pendingInitialSeekMs)
            .coerceAtLeast(startPositionMs)
        val isDeadTorrent = lastTorrActivePeers == 0 &&
            lastTorrSpeedBytes < 10_000.0 &&
            lastTorrPreloadPct < 5
        if (isDeadTorrent && deadTorrentSinceMs > 0L) {
            val deadElapsed = now - deadTorrentSinceMs
            if (deadElapsed >= 18_000L &&
                now >= altSourcesSnoozeUntilMs &&
                altSourcesDialogShownForHash != torrentHash
            ) {
                altSourcesDialogShownForHash = torrentHash
                AppLogger.w(
                    "Player",
                    "Раздача без сидов (${deadElapsed / 1000L}с, $lastTorrStatsLogSummary) — предлагаем альтернативы"
                )
                showAlternativeSourcesDialog(resumeAtMs = resumeSnapshot)
                return
            }
        }

        if (now < stallRecoveryCooldownUntilMs) return

        if (!isVlcEngine()) {
            val exo = player ?: return
            val stuckBuffering = isBufferingNow || exo.playbackState == Player.STATE_BUFFERING
            if (!stuckBuffering) return

            val bufPos = exo.bufferedPosition
            if (bufPos > lastMedia3BufferedPosMs + 500L) {
                lastMedia3BufferedPosMs = bufPos
                lastMedia3BufferProgressAtMs = now
            }
            if (bufferingStartedAtMs <= 0L) {
                bufferingStartedAtMs = now
                return
            }

            val stalledMs = now - bufferingStartedAtMs
            val noBufGrowthMs = if (lastMedia3BufferProgressAtMs > 0L) now - lastMedia3BufferProgressAtMs else stalledMs
            if (stalledMs < 22_000L || noBufGrowthMs < 12_000L) return

            stallRecoveryCooldownUntilMs = now + 20_000L
            val aheadSec = ((bufPos - exo.currentPosition).coerceAtLeast(0L) / 1000L)
            val resumeAt = pos.coerceAtLeast(pendingInitialSeekMs)
            val reason = if (aheadSec >= 4L) "decoder_stall" else "network_stall"

            if (media3StallRecoveryCount == 0) {
                media3StallRecoveryCount++
                AppLogger.w(
                    "Player",
                    "Media3 $reason (stalled=${stalledMs}мс, ahead=${aheadSec}с, $lastTorrStatsLogSummary) — переподключение с ${resumeAt}мс"
                )
                showQuickHudMessage("↻ Переподключение потока…")
                prepareMedia3(streamUrl, resumeAt)
            } else if (!hasAutoFallenBackToVlc) {
                hasAutoFallenBackToVlc = true
                AppLogger.w(
                    "Player",
                    "Media3 повторный $reason (stalled=${stalledMs}мс, ahead=${aheadSec}с, $lastTorrStatsLogSummary) — автоподхват во встроенном VLC с ${resumeAt}мс"
                )
                Toast.makeText(
                    this,
                    "Media3 завис — автоподхват во встроенном VLC…",
                    Toast.LENGTH_SHORT
                ).show()
                switchToVlcEngine(resumeAt)
            } else {
                media3StallRecoveryCount++
                AppLogger.w(
                    "Player",
                    "Media3 повтор ($reason, stalled=${stalledMs}мс, $lastTorrStatsLogSummary) с ${resumeAt}мс"
                )
                showQuickHudMessage("↻ Повтор буферизации…")
                prepareMedia3(streamUrl, resumeAt)
            }
        } else {
            val vlc = vlcPlayer ?: return
            val resumeAt = pos.coerceAtLeast(vlcRequestedStartTimeMs).coerceAtLeast(pendingInitialSeekMs)

            // Case 1: Genuine decoder stall (VLC buffer is already >= 95%, yet time stopped moving for 14s)
            val bufferIsFull = vlcBufferPercent >= 95
            val decoderFrozenMs = when {
                bufferIsFull && lastAdvanceRealTimeMs > 0L && playing -> now - lastAdvanceRealTimeMs
                bufferIsFull && isBufferingNow && bufferingStartedAtMs > 0L -> now - bufferingStartedAtMs
                else -> 0L
            }
            if (bufferIsFull && decoderFrozenMs >= 14_000L) {
                stallRecoveryCooldownUntilMs = now + 20_000L
                if (!vlcForceSoftwareDecode) {
                    vlcStallRecoveryCount++
                    vlcForceSoftwareDecode = true
                    updateEngineSwitchUi()
                    AppLogger.w(
                        "Player",
                        "VLC decoder_stall (buf=${vlcBufferPercent}%, frozen=${decoderFrozenMs}мс, $lastTorrStatsLogSummary) — переход на программный декодер с ${resumeAt}мс"
                    )
                    Toast.makeText(
                        this,
                        "VLC: переход на программное декодирование…",
                        Toast.LENGTH_SHORT
                    ).show()
                    prepareVlcMedia(streamUrl, resumeAt)
                } else {
                    vlcStallRecoveryCount++
                    AppLogger.w(
                        "Player",
                        "VLC decoder_stall в SW (buf=${vlcBufferPercent}%, frozen=${decoderFrozenMs}мс, $lastTorrStatsLogSummary) — переподключение с ${resumeAt}мс"
                    )
                    showQuickHudMessage("↻ VLC: перезапуск потока…")
                    prepareVlcMedia(streamUrl, resumeAt)
                }
                return
            }

            // Case 2: Network / TorrServe buffering stall (never downgrade HW decoder on slow network)
            if (!isBufferingNow) return
            if (bufferingStartedAtMs <= 0L) {
                bufferingStartedAtMs = now
                return
            }

            val stalledMs = now - bufferingStartedAtMs
            val noBufGrowthMs = if (vlcLastBufferProgressAtMs > 0L) now - vlcLastBufferProgressAtMs else stalledMs
            // Do not interrupt while VLC buffer percentage is actively climbing
            if (stalledMs < 22_000L || noBufGrowthMs < 12_000L) return

            if (isDeadTorrent && altSourcesDialogShownForHash != torrentHash && now >= altSourcesSnoozeUntilMs) {
                altSourcesDialogShownForHash = torrentHash
                AppLogger.w("Player", "VLC: раздача без сидов ($lastTorrStatsLogSummary) — открываем альтернативы")
                showAlternativeSourcesDialog(resumeAtMs = resumeAt)
                return
            }
            if (isDeadTorrent && vlcStallRecoveryCount >= 1) {
                // Already retried once on a dead torrent; don't hammer TorrServe
                stallRecoveryCooldownUntilMs = now + 25_000L
                return
            }
            stallRecoveryCooldownUntilMs = now + 20_000L
            vlcStallRecoveryCount++
            AppLogger.w(
                "Player",
                "VLC network_stall (buf=${vlcBufferPercent}%, stalled=${stalledMs}мс, noGrowth=${noBufGrowthMs}мс, sw=$vlcForceSoftwareDecode, $lastTorrStatsLogSummary) — переподключение с ${resumeAt}мс"
            )
            showQuickHudMessage("↻ VLC: переподключение потока…")
            prepareVlcMedia(streamUrl, resumeAt)
        }
    }

    private fun clearBufferingOverlay() {
        isBufferingNow = false
        bufferingStartedAtMs = 0L
        if (layoutPlayerBuffering.visibility == View.VISIBLE) {
            layoutPlayerBuffering.visibility = View.GONE
        }
        if (btnStartFromBeginning.visibility == View.VISIBLE) {
            btnStartFromBeginning.visibility = View.GONE
        }
    }

    private fun updateBufferingOverlayState() {
        if (isVlcEngine()) {
            val recentlyAdvanced = vlcLastProgressAtMs > 0L &&
                (System.currentTimeMillis() - vlcLastProgressAtMs) < 1_300L
            if (recentlyAdvanced) {
                clearBufferingOverlay()
                return
            }
        } else {
            val exo = player
            if (exo != null && exo.playbackState == Player.STATE_READY) {
                clearBufferingOverlay()
                return
            }
        }
        if (!isBufferingNow) {
            if (layoutPlayerBuffering.visibility == View.VISIBLE) {
                layoutPlayerBuffering.visibility = View.GONE
            }
            return
        }
        val elapsed = System.currentTimeMillis() - bufferingStartedAtMs
        if (elapsed >= 900L && layoutOsd.visibility != View.VISIBLE) {
            if (layoutPlayerBuffering.visibility != View.VISIBLE) {
                val resumeTarget = pendingInitialSeekMs.coerceAtLeast(vlcRequestedStartTimeMs)
                tvBufferingTitle.text = if (resumeTarget > 2_000L) {
                    "Подготовка потока (${formatDuration(resumeTarget)})…"
                } else {
                    getString(R.string.player_buffering_title)
                }
                if (tvBufferingStats.text.isNullOrBlank()) {
                    tvBufferingStats.text = torrentStatsSummary.ifBlank { "Загрузка данных из TorrServe…" }
                }
                layoutPlayerBuffering.visibility = View.VISIBLE
            }
        }
        if (elapsed >= 5_500L && (pendingInitialSeekMs > 2_000L || vlcRequestedStartTimeMs > 2_000L || startPositionMs > 2_000L)) {
            if (btnStartFromBeginning.visibility != View.VISIBLE) {
                btnStartFromBeginning.visibility = View.VISIBLE
                btnStartFromBeginning.requestFocus()
            }
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

    private fun applyVlcRememberedTracks() {
        val vlc = vlcPlayer ?: return
        val audioList = vlc.getAudioTracks()?.filter { it.id != -1 }.orEmpty()
        val spuList = vlc.getSpuTracks()?.filter { it.id != -1 }.orEmpty()
        if (audioList.isEmpty() && spuList.isEmpty()) return
        appliedSavedTracksForItem = true

        if (audioList.isNotEmpty()) {
            val savedAudioLabel = playerPrefs.preferredAudioLabel
            val savedAudioLang = playerPrefs.preferredAudioLang
            val match = findBestMatchingVlcTrack(audioList, savedAudioLabel, savedAudioLang)
            if (match != null && match.id != vlc.getAudioTrack()) {
                runCatching { vlc.setAudioTrack(match.id) }
            }
        }

        if (playerPrefs.subtitlesDisabled) {
            if (vlc.getSpuTrack() != -1) {
                runCatching { vlc.setSpuTrack(-1) }
            }
        } else if (spuList.isNotEmpty()) {
            val savedSubLabel = playerPrefs.preferredSubLabel
            val savedSubLang = playerPrefs.preferredSubLang
            val match = findBestMatchingVlcTrack(spuList, savedSubLabel, savedSubLang)
            if (match != null && match.id != vlc.getSpuTrack()) {
                runCatching { vlc.setSpuTrack(match.id) }
            }
        }
    }

    private fun findBestMatchingVlcTrack(
        tracks: List<VlcMediaPlayer.TrackDescription>,
        preferredLabel: String,
        preferredLang: String
    ): VlcMediaPlayer.TrackDescription? {
        val wantLabel = preferredLabel.trim().lowercase(Locale.ROOT)
        val wantLang = preferredLang.trim().lowercase(Locale.US)

        if (wantLabel.isNotBlank()) {
            tracks.firstOrNull { it.name.trim().lowercase(Locale.ROOT) == wantLabel }?.let { return it }
        }
        if (wantLang.isNotBlank()) {
            val tokens = langAliases(wantLang)
            tracks.firstOrNull { td ->
                val lower = td.name.lowercase(Locale.ROOT)
                tokens.any { token -> lower.contains(token) }
            }?.let { return it }
        }
        return null
    }

    private fun langAliases(code: String): List<String> {
        val c = code.lowercase(Locale.US)
        return when {
            c.startsWith("ru") -> listOf("rus", "russian", "рус", "[ru]", "(ru)")
            c.startsWith("en") -> listOf("eng", "english", "англ", "[en]", "(en)")
            c.startsWith("uk") -> listOf("ukr", "ukrainian", "укр", "[uk]", "(uk)")
            else -> listOf(c, c.take(2)).filter { it.isNotBlank() }
        }
    }

    private fun inferLangCodeFromVlcTrackName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.contains("rus") || lower.contains("russian") || lower.contains("рус") -> "rus"
            lower.contains("eng") || lower.contains("english") || lower.contains("англ") -> "eng"
            lower.contains("ukr") || lower.contains("ukrainian") || lower.contains("укр") -> "ukr"
            else -> ""
        }
    }

    private fun applyRememberedTracks(tracks: Tracks) {
        val selector = trackSelector ?: return
        var hasAudio = false
        var hasText = false
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.length > 0) hasAudio = true
            if (group.type == C.TRACK_TYPE_TEXT && group.length > 0) hasText = true
        }
        if (!hasAudio && !hasText) return
        appliedSavedTracksForItem = true

        val builder = selector.buildUponParameters()
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
            selector.setParameters(builder)
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

        hasRetriedCodecFallback = false
        vlcRetriedSoftwareDecode = false
        media3StallRecoveryCount = 0
        vlcStallRecoveryCount = 0
        stallRecoveryCooldownUntilMs = 0L
        val nextFile = episodes[targetIndex]
        currentEpisodeIndex = targetIndex
        fileId = nextFile.id
        episodeLabel = nextFile.fileName

        if (torrentHash.isNotBlank()) {
            storage.setLastEpisodeForTorrent(torrentHash, nextFile.id)
        }

        val hashOrLink = torrentHash.ifBlank { link }
        streamUrl = torrServeClient.buildStreamUrl(hashOrLink, nextFile.id, nextFile.fileName)
        startPositionMs = storage.getSavedPositionMs(link, nextFile.id, torrentHash)

        updateSeriesControlsUi()
        hideOsd()
        showQuickHudMessage("▶ ${nextFile.fileName}")

        uiScope.launch {
            withContext(Dispatchers.IO) {
                if (hashOrLink.isNotBlank()) {
                    torrServeClient.triggerPreload(hashOrLink, nextFile.id)
                }
            }
            prepareActiveMedia(streamUrl, startPositionMs)
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
        fun refreshSubtitle() {
            val watchedCount = if (torrentHash.isNotBlank()) {
                episodes.count { storage.isEpisodeCompleted(torrentHash, it.id) }
            } else {
                0
            }
            tvCount.text = "Серия $curPos из ${episodes.size} (просмотрено: $watchedCount) • OK — выбрать • Удерж. OK — отметка"
        }
        refreshSubtitle()

        lateinit var epAdapter: SmartEpisodeAdapter
        epAdapter = SmartEpisodeAdapter(
            files = episodes,
            currentPlayingFileId = fileId,
            lastOpenedFileId = if (torrentHash.isNotBlank()) storage.getLastEpisodeForTorrent(torrentHash) else null,
            progressProvider = { id ->
                if (torrentHash.isNotBlank()) storage.getEpisodeProgress(torrentHash, id) else null
            },
            completedChecker = { id ->
                torrentHash.isNotBlank() && storage.isEpisodeCompleted(torrentHash, id)
            },
            onEpisodeClick = { selected ->
                dialog.dismiss()
                val idx = episodes.indexOfFirst { it.id == selected.id }
                if (idx >= 0 && idx != currentEpisodeIndex) {
                    switchToEpisode(idx)
                }
            },
            onEpisodeToggleWatched = { selected ->
                if (torrentHash.isNotBlank()) {
                    val nowWatched = storage.toggleEpisodeWatched(torrentHash, selected.id)
                    epAdapter.notifyDataSetChanged()
                    refreshSubtitle()
                    Toast.makeText(
                        this,
                        if (nowWatched) "✓ Отмечено как просмотренное" else "Отметка просмотра снята",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        val lm = LinearLayoutManager(this)
        rvEpisodes.layoutManager = lm
        rvEpisodes.adapter = epAdapter
        dialog.show()

        val focusIdx = currentEpisodeIndex.coerceIn(0, episodes.lastIndex)
        val topOffset = (72 * resources.displayMetrics.density).toInt()
        lm.scrollToPositionWithOffset(focusIdx, topOffset)
        rvEpisodes.post {
            val vh = rvEpisodes.findViewHolderForAdapterPosition(focusIdx)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                lm.scrollToPositionWithOffset(focusIdx, topOffset)
                rvEpisodes.post {
                    rvEpisodes.findViewHolderForAdapterPosition(focusIdx)?.itemView?.requestFocus()
                        ?: rvEpisodes.getChildAt(0)?.requestFocus()
                }
            }
        }
    }

    private fun checkAutoNextTrigger() {
        if (!autoNextEnabled || nextCardActive || nextCardDismissedForCurrent || !hasNextEpisode()) return
        if (!isCurrentlyPlaying()) return
        val dur = currentDurationMs()
        if (dur < 180_000L) return
        val remainingMs = dur - currentPositionMs()
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
        layoutPlayerBuffering.visibility = View.GONE
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
        if (isCurrentlyPlaying()) {
            pausePlayback()
        } else {
            resumePlayback()
        }
        updateOsdProgress()
    }

    private fun seekBy(deltaMs: Long, showQuickHud: Boolean) {
        val duration = currentDurationMs().takeIf { it > 0L } ?: return
        val target = (currentPositionMs() + deltaMs).coerceIn(0L, duration)
        seekToPositionMs(target)
        updateOsdProgress()
        if (showQuickHud) {
            val sec = kotlin.math.abs(deltaMs / 1000L)
            val stepLabel = if (sec >= 60L) "${sec / 60L} мин" else "${sec}с"
            val arrow = if (deltaMs < 0L) "◀ -$stepLabel" else "+$stepLabel ▶"
            showQuickHudMessage("$arrow   ${formatDuration(target)} / ${formatDuration(duration)}")
        }
    }

    private fun updateOsdProgress() {
        val cur = currentPositionMs()
        val dur = currentDurationMs()
        tvTotalTime.text = if (dur > 0L) formatDuration(dur) else "--:--"
        if (!isUserSeeking) {
            tvCurrentTime.text = formatDuration(cur)
            if (dur > 0L) {
                seekBar.progress = ((cur * 1000L) / dur).toInt().coerceIn(0, 1000)
            } else {
                seekBar.progress = 0
            }
        }
        btnPlayPause.text = if (isCurrentlyPlaying()) "❚❚" else "▶"
    }

    private fun updateStreamInfoText() {
        val parts = mutableListOf<String>()

        if (isVlcEngine()) {
            parts.add(if (vlcForceSoftwareDecode) "VLC (SW)" else "VLC")
            val vlc = vlcPlayer
            val vt = runCatching { vlc?.getCurrentVideoTrack() }.getOrNull()
            if (vt != null) {
                if (vt.width > 0 && vt.height > 0) {
                    parts.add("${vt.width}×${vt.height}")
                }
                val vCodec = formatVlcVideoCodec(vt.codec ?: vt.originalCodec)
                if (vCodec.isNotBlank()) parts.add(vCodec)
                if (vt.frameRateNum > 0 && vt.frameRateDen > 0) {
                    val fps = vt.frameRateNum.toFloat() / vt.frameRateDen.toFloat()
                    if (fps in 1f..240f) {
                        parts.add(
                            String.format(Locale.US, "%.2f fps", fps)
                                .removeSuffix(".00 fps")
                                .let { s -> if (s.endsWith("fps")) s else "$s fps" }
                        )
                    }
                }
                if (vt.bitrate > 0) {
                    val mbps = vt.bitrate / 1_000_000.0
                    if (mbps >= 0.5) {
                        parts.add(String.format(Locale.US, "%.1f Мбит/с", mbps))
                    }
                }
            }

            val mediaRef = runCatching { vlc?.media }.getOrNull()
            if (mediaRef != null) {
                try {
                    val activeAudioId = vlc?.getAudioTrack() ?: -1
                    var matchedAudio: IMedia.AudioTrack? = null
                    var firstAudio: IMedia.AudioTrack? = null
                    val count = mediaRef.trackCount
                    for (i in 0 until count) {
                        val tr = mediaRef.getTrack(i)
                        if (tr is IMedia.AudioTrack) {
                            if (firstAudio == null) firstAudio = tr
                            if (tr.id == activeAudioId) {
                                matchedAudio = tr
                                break
                            }
                        }
                    }
                    val at = matchedAudio ?: firstAudio
                    if (at != null) {
                        val aCodec = formatVlcAudioCodec(at.codec ?: at.originalCodec)
                        val ch = formatChannels(at.channels)
                        val audioText = listOf(aCodec, ch).filter { it.isNotBlank() }.joinToString(" ")
                        if (audioText.isNotBlank()) parts.add(audioText)
                    }
                } finally {
                    runCatching { mediaRef.release() }
                }
            }
        } else {
            val exo = player
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
        }

        if (torrentStatsSummary.isNotBlank()) {
            parts.add(torrentStatsSummary)
        }

        tvStreamInfo.text = parts.joinToString("  •  ")
        tvStreamInfo.visibility = if (parts.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatVlcVideoCodec(fourcc: String?): String {
        val c = fourcc?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            c.contains("dvhe") || c.contains("dvh1") || c.contains("dovi") -> "Dolby Vision"
            c.contains("hevc") || c.contains("h265") || c.contains("hev1") || c.contains("hvc1") -> "HEVC"
            c.contains("h264") || c.contains("avc") || c.contains("x264") -> "H.264"
            c.contains("av01") || c.contains("av1") -> "AV1"
            c.contains("vp9") -> "VP9"
            c.contains("vp8") -> "VP8"
            c.contains("wvc1") || c.contains("vc-1") || c.contains("vc1") -> "VC-1"
            c.contains("mpgv") || c.contains("mpg2") || c.contains("mpeg") -> "MPEG-2"
            else -> c.uppercase(Locale.US)
        }
    }

    private fun formatVlcAudioCodec(fourcc: String?): String {
        val c = fourcc?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            c.contains("eac3") || c.contains("ec-3") -> "E-AC3"
            c.contains("a52") || c.contains("ac3") || c.contains("ac-3") -> "AC3"
            c.contains("dts") -> "DTS"
            c.contains("mlp") || c.contains("trhd") || c.contains("truehd") -> "TrueHD"
            c.contains("flac") -> "FLAC"
            c.contains("opus") -> "Opus"
            c.contains("mp4a") || c.contains("aac") -> "AAC"
            c.contains("mpga") || c.contains("mp3") -> "MP3"
            c.contains("vorb") -> "Vorbis"
            else -> c.uppercase(Locale.US)
        }
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
        if (isVlcEngine()) {
            showVlcAudioTrackDialog()
            return
        }
        val exo = player ?: return
        val selector = trackSelector ?: return
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
                selector.setParameters(
                    selector.buildUponParameters()
                        .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, chosen.trackIndex))
                )
                updateStreamInfoText()
            }
            .show()
    }

    private fun showVlcAudioTrackDialog() {
        val vlc = vlcPlayer ?: return
        val tracks = vlc.getAudioTracks()?.filter { it.id != -1 }.orEmpty()
        if (tracks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Аудиодорожки")
                .setMessage("Дополнительные аудиодорожки не найдены")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val activeId = vlc.getAudioTrack()
        val labels = tracks.map { td ->
            val mark = if (td.id == activeId) " ✓" else ""
            "${td.name}$mark"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите аудиодорожку (VLC)")
            .setItems(labels) { _, which ->
                val chosen = tracks[which]
                runCatching { vlc.setAudioTrack(chosen.id) }
                playerPrefs.preferredAudioLabel = chosen.name
                val inferred = inferLangCodeFromVlcTrackName(chosen.name)
                if (inferred.isNotBlank()) {
                    playerPrefs.preferredAudioLang = inferred
                }
                applyVlcAudioBoost()
                updateStreamInfoText()
            }
            .show()
    }

    private fun showSubtitleTrackDialog() {
        if (isVlcEngine()) {
            showVlcSubtitleTrackDialog()
            return
        }
        val exo = player ?: return
        val selector = trackSelector ?: return
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
                val builder = selector.buildUponParameters()
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
                selector.setParameters(builder)
            }
            .show()
    }

    private fun showVlcSubtitleTrackDialog() {
        val vlc = vlcPlayer ?: return
        val tracks = vlc.getSpuTracks()?.filter { it.id != -1 }.orEmpty()
        val activeId = vlc.getSpuTrack()

        val labels = mutableListOf<String>()
        labels.add(if (activeId == -1) "Выключить субтитры ✓" else "Выключить субтитры")
        for (td in tracks) {
            val mark = if (td.id == activeId) " ✓" else ""
            labels.add("${td.name}$mark")
        }

        AlertDialog.Builder(this)
            .setTitle("Субтитры (VLC)")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    playerPrefs.subtitlesDisabled = true
                    runCatching { vlc.setSpuTrack(-1) }
                } else {
                    val chosen = tracks[which - 1]
                    playerPrefs.subtitlesDisabled = false
                    playerPrefs.preferredSubLabel = chosen.name
                    val inferred = inferLangCodeFromVlcTrackName(chosen.name)
                    if (inferred.isNotBlank()) {
                        playerPrefs.preferredSubLang = inferred
                    }
                    runCatching { vlc.setSpuTrack(chosen.id) }
                }
            }
            .show()
    }

    private fun savePlaybackProgress(completed: Boolean = false) {
        if (!isVlcEngine() && player == null) return
        if (isVlcEngine() && vlcPlayer == null && vlcLastKnownTimeMs <= 0L) return
        val dur = currentDurationMs()
        val rawPos = currentPositionMs()
        val pos = if (completed && dur > 0L) dur else rawPos
        if (link.isNotBlank() && (completed || pos > 1_500L)) {
            storage.saveWatchPosition(
                title = title,
                link = link,
                fileId = fileId,
                episodeLabel = episodeLabel,
                positionMs = pos,
                durationMs = dur
            )
        }
        if (torrentHash.isNotBlank() && (completed || pos > 1_500L)) {
            storage.saveEpisodeProgress(
                hash = torrentHash,
                fileId = fileId,
                positionMs = pos,
                durationMs = dur,
                completed = completed
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

    private fun cleanMovieQueryFromTitle(rawTitle: String, includeYear: Boolean = true): String {
        val raw = rawTitle.trim()
        if (raw.isBlank()) return ""

        // Extract standalone 4-digit release year (avoiding ranges like 2007-2019)
        val yearMatches = YEAR_REGEX.findAll(raw).map { it.groupValues[1] }.toList()
        val releaseYear = if (yearMatches.size == 1 && !YEAR_RANGE_REGEX.containsMatchIn(raw)) {
            yearMatches.first()
        } else {
            ""
        }

        var mainPart = raw
            .substringBefore(" / ")
            .substringBefore('/')
            .substringBefore('[')
            .substringBefore('(')
            .substringBefore('|')
            .trim()

        mainPart = RELEASE_TAGS_REGEX.replace(mainPart, " ")
            .replace(Regex("[._]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', ':', '—', '–', ',', ';')

        if (mainPart.length < 2) {
            mainPart = raw.replace(Regex("[\\[\\]()/|]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(40)
        }

        return if (includeYear && releaseYear.isNotBlank() && !mainPart.contains(releaseYear)) {
            "$mainPart $releaseYear"
        } else {
            mainPart
        }
    }

    private fun showAlternativeSourcesDialog(resumeAtMs: Long) {
        if (isFinishing || isDestroyed) return
        if (altSourcesDialog?.isShowing == true) return

        pausePlayback()
        hideOsd()
        layoutPlayerBuffering.visibility = View.GONE

        val cleanWithYear = cleanMovieQueryFromTitle(title, includeYear = true)
        val cleanNoYear = cleanMovieQueryFromTitle(title, includeYear = false)
        val displayQuery = cleanWithYear.ifBlank { cleanNoYear.ifBlank { title.take(36) } }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_alt_sources)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.84).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )
        altSourcesDialog = dialog

        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvAltSubtitle)
        val layoutLoading = dialog.findViewById<LinearLayout>(R.id.layoutAltLoading)
        val tvLoadingText = dialog.findViewById<TextView>(R.id.tvAltLoadingText)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvAltEmpty)
        val rvAlt = dialog.findViewById<RecyclerView>(R.id.rvAltTorrents)
        val btnWait = dialog.findViewById<TextView>(R.id.btnAltWait)
        val btnManual = dialog.findViewById<TextView>(R.id.btnAltManualSearch)

        val posText = if (resumeAtMs > 2_000L) " • продолжим с ${formatDuration(resumeAtMs)}" else ""
        tvSubtitle.text = "Поиск: «$displayQuery» (пиры: $lastTorrActivePeers/$lastTorrTotalPeers)$posText"
        tvLoadingText.text = "Ищем живые раздачи для «$displayQuery»…"

        val altAdapter = TorrentAdapter(
            isFavorite = { storage.isFavoriteTorrent(it) },
            onItemClick = { selected ->
                dialog.dismiss()
                switchToAlternativeTorrent(selected, resumeAtMs)
            },
            onToggleFavorite = { item ->
                val added = storage.toggleFavoriteTorrent(item)
                Toast.makeText(
                    this,
                    if (added) "★ Добавлено в избранное" else "Убрано из избранного",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        rvAlt.layoutManager = LinearLayoutManager(this)
        rvAlt.adapter = altAdapter

        btnWait.setOnClickListener {
            altSourcesSnoozeUntilMs = System.currentTimeMillis() + 45_000L
            deadTorrentSinceMs = 0L
            altSourcesDialogShownForHash = ""
            dialog.dismiss()
            resumePlayback()
            showQuickHudMessage("⏳ Продолжаем ожидание пиров…")
        }

        btnManual.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_AUTO_SEARCH_QUERY, displayQuery)
            }
            startActivity(intent)
            finish()
        }

        dialog.setOnDismissListener {
            altSearchJob?.cancel()
            if (altSourcesDialog === dialog) {
                altSourcesDialog = null
            }
        }

        dialog.show()
        btnManual.requestFocus()

        altSearchJob?.cancel()
        altSearchJob = uiScope.launch {
            val currentHashLower = torrentHash.trim().lowercase(Locale.US)
            val currentLinkTrim = link.trim()

            fun filterLive(items: List<TorrentItem>): List<TorrentItem> {
                return items
                    .filter { it.seeds > 0 && it.playableLink.isNotBlank() }
                    .filterNot { item ->
                        val sameLink = currentLinkTrim.isNotBlank() && item.playableLink.trim() == currentLinkTrim
                        val sameHash = currentHashLower.isNotBlank() &&
                            item.playableLink.contains(currentHashLower, ignoreCase = true)
                        sameLink || sameHash
                    }
                    .sortedByDescending { it.seeds }
                    .take(25)
            }

            var live = filterLive(runCatching { searchEngine.search(displayQuery).items }.getOrDefault(emptyList()))
            if (live.isEmpty() && cleanNoYear.isNotBlank() && !cleanNoYear.equals(displayQuery, ignoreCase = true)) {
                tvLoadingText.text = "Пробуем запрос «$cleanNoYear»…"
                live = filterLive(runCatching { searchEngine.search(cleanNoYear).items }.getOrDefault(emptyList()))
            }

            if (!dialog.isShowing) return@launch
            layoutLoading.visibility = View.GONE
            if (live.isNotEmpty()) {
                tvEmpty.visibility = View.GONE
                rvAlt.visibility = View.VISIBLE
                altAdapter.submitList(live)
                rvAlt.post {
                    rvAlt.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        ?: rvAlt.getChildAt(0)?.requestFocus()
                }
            } else {
                rvAlt.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                btnManual.requestFocus()
            }
        }
    }

    private fun switchToAlternativeTorrent(item: TorrentItem, resumeAtMs: Long) {
        val newLink = item.playableLink
        if (newLink.isBlank()) {
            Toast.makeText(this, "У раздачи нет ссылки", Toast.LENGTH_SHORT).show()
            return
        }
        altSwitchJob?.cancel()
        altSwitchJob = uiScope.launch {
            isBufferingNow = true
            bufferingStartedAtMs = System.currentTimeMillis()
            tvBufferingTitle.text = "Подключение новой раздачи…"
            tvBufferingStats.text = item.title.take(64)
            layoutPlayerBuffering.visibility = View.VISIBLE
            btnStartFromBeginning.visibility = View.GONE

            val oldHash = torrentHash
            val added = runCatching {
                withContext(Dispatchers.IO) {
                    if (oldHash.isNotBlank()) {
                        runCatching { torrServeClient.stopAndRemoveTorrent(oldHash) }
                    }
                    torrServeClient.addTorrent(newLink, item.title)
                }
            }.onFailure { err ->
                AppLogger.e("Player", "Ошибка переключения на альтернативу: ${err.message}", err)
            }.getOrNull()

            if (added == null || isFinishing || isDestroyed) {
                layoutPlayerBuffering.visibility = View.GONE
                Toast.makeText(this@BuiltInPlayerActivity, "Не удалось подключить альтернативную раздачу", Toast.LENGTH_LONG).show()
                return@launch
            }

            val newHash = added.hash.ifBlank { extractHashFromStreamOrLink("", newLink) }
            val files = if (added.files.isNotEmpty()) {
                val vids = torrServeClient.filterAndSortVideos(added.files)
                if (vids.isNotEmpty()) vids else added.files
            } else {
                torrServeClient.waitForVideoFiles(newHash.ifBlank { newLink }, timeoutMs = 14_000L)
            }

            val chosenFile = when {
                files.isEmpty() -> TorrServeFile(id = 1, path = "${item.title}.mkv", length = 0L)
                files.size == 1 -> files.first()
                currentEpisodeIndex in files.indices -> files[currentEpisodeIndex]
                else -> files.maxByOrNull { it.length } ?: files.first()
            }

            val hashOrLink = newHash.ifBlank { newLink }
            withContext(Dispatchers.IO) {
                runCatching { torrServeClient.triggerPreload(hashOrLink, chosenFile.id) }
            }

            link = newLink
            torrentHash = newHash
            title = item.title
            fileId = chosenFile.id
            episodes = files
            currentEpisodeIndex = files.indexOfFirst { it.id == chosenFile.id }
            episodeLabel = if (files.size > 1) chosenFile.fileName else ""
            streamUrl = torrServeClient.buildStreamUrl(hashOrLink, chosenFile.id, chosenFile.fileName)
            startPositionMs = resumeAtMs

            if (newHash.isNotBlank()) {
                storage.setLastEpisodeForTorrent(newHash, chosenFile.id)
                if (!newHash.equals(initialTorrentHash, ignoreCase = true)) {
                    lastSwitchedTorrentHash = newHash
                }
            }
            if (audioBoostDb > 0) {
                storage.setAudioBoostForMedia(link, torrentHash, audioBoostDb)
            }

            // Reset stall & dead-torrent trackers for the fresh source
            deadTorrentSinceMs = 0L
            altSourcesDialogShownForHash = ""
            altSourcesSnoozeUntilMs = 0L
            lastTorrActivePeers = -1
            lastTorrSpeedBytes = 0.0
            lastTorrPreloadPct = 0
            media3StallRecoveryCount = 0
            vlcStallRecoveryCount = 0
            hasRetriedCodecFallback = false
            vlcRetriedSoftwareDecode = false
            vlcThrashTimestamps.clear()

            updateSeriesControlsUi()
            showQuickHudMessage("▶ Новая раздача (${item.seeds} сид.)")
            AppLogger.i("Player", "Переключено на альтернативную раздачу (${item.seeds} сид., pos=${resumeAtMs}мс): ${item.title}")
            prepareActiveMedia(streamUrl, resumeAtMs)
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EPISODE_LABEL = "extra_episode_label"
        const val EXTRA_LINK = "extra_link"
        const val EXTRA_FILE_ID = "extra_file_id"
        const val EXTRA_START_POS_MS = "extra_start_pos_ms"
        const val EXTRA_PLAYER_ENGINE = "extra_player_engine"
        const val EXTRA_FROM_HISTORY = "extra_from_history"

        @Volatile
        private var lastSwitchedTorrentHash: String = ""

        fun consumeSwitchedTorrentHash(): String {
            val h = lastSwitchedTorrentHash
            lastSwitchedTorrentHash = ""
            return h
        }

        private val BOOST_STEPS_DB = intArrayOf(0, 3, 6, 9, 12)
        private val BTIH_REGEX = Regex("btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("\\b((?:19|20)\\d{2})\\b")
        private val YEAR_RANGE_REGEX = Regex("(?:19|20)\\d{2}\\s*[-–—]\\s*(?:19|20)\\d{2}")
        private val RELEASE_TAGS_REGEX = Regex(
            "\\b(2160p|1080p|720p|480p|4k|uhd|hdr10\\+?|hdr|dv|dolby\\s*vision|hevc|h\\.?265|x265|h\\.?264|x264|av1|10-?bit|web-?dl(?:rip)?|bdrip|bluray|blu-?ray|remux|hdrip|dvdrip|imax|hybrid|dub|mvo|dvo|avo|sub|rus|eng|ukr|ру|ст)\\b",
            RegexOption.IGNORE_CASE
        )
    }
}
