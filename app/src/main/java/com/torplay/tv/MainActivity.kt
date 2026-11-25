package com.torplay.tv

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class QualityFilter { ALL, UHD_4K, FHD_1080, HD_720, HDR }
    private enum class SortMode { SEEDS, SIZE, DATE }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var storage: StorageManager
    private lateinit var searchEngine: SearchEngine
    private lateinit var torrServeClient: TorrServeClient
    private lateinit var torrServeStarter: TorrServeStarter
    private lateinit var playerController: PlayerController
    private lateinit var playerPrefs: PlayerPrefs
    private lateinit var maintenanceManager: MaintenanceManager
    private lateinit var updateManager: UpdateManager
    private lateinit var tmdbClient: TmdbClient
    private lateinit var kinopoiskClient: KinopoiskClient
    private lateinit var torrentShowcaseClient: TorrentShowcaseClient
    private lateinit var posterLoader: PosterLoader
    private lateinit var adapter: TorrentAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var favoriteTorrentAdapter: FavoriteTorrentAdapter
    private lateinit var recentSearchAdapter: RecentSearchAdapter
    private lateinit var tmdbTrendingAdapter: CatalogAdapter
    private lateinit var tmdbNowPlayingAdapter: CatalogAdapter
    private lateinit var tmdbSeriesAdapter: CatalogAdapter
    private lateinit var tmdbSearchAdapter: CatalogAdapter
    private lateinit var torrentTrendingAdapter: TorrentShowcaseAdapter
    private lateinit var torrentNewAdapter: TorrentShowcaseAdapter
    private lateinit var torrentSeriesAdapter: TorrentShowcaseAdapter

    private lateinit var btnTorrServeStatus: LinearLayout
    private lateinit var viewStatusDot: View
    private lateinit var tvTorrServeStatus: TextView
    private lateinit var btnFavorites: TextView
    private lateinit var btnHistory: TextView
    private lateinit var btnSettings: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: TextView
    private lateinit var btnVoiceSearch: LinearLayout
    private lateinit var btnSearch: TextView
    private lateinit var rvRecentSearches: RecyclerView
    private lateinit var layoutFilters: LinearLayout
    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilter4k: TextView
    private lateinit var btnFilter1080: TextView
    private lateinit var btnFilter720: TextView
    private lateinit var btnFilterHdr: TextView
    private lateinit var btnFilterNoCam: TextView
    private lateinit var btnFilterLiveSeeds: TextView
    private lateinit var btnSort: TextView
    private lateinit var tvSearchMeta: TextView
    private lateinit var scrollHomeShelves: ScrollView
    private lateinit var layoutHomeShelves: LinearLayout
    private lateinit var layoutTmdbNotice: LinearLayout
    private lateinit var progressTmdb: ProgressBar
    private lateinit var tvTmdbNotice: TextView
    private lateinit var tvTmdbTrendingHeader: TextView
    private lateinit var rvTmdbTrending: RecyclerView
    private lateinit var tvTmdbNowPlayingHeader: TextView
    private lateinit var rvTmdbNowPlaying: RecyclerView
    private lateinit var tvTmdbSeriesHeader: TextView
    private lateinit var rvTmdbSeries: RecyclerView
    private lateinit var tvTorrentTrendingHeader: TextView
    private lateinit var rvTorrentTrending: RecyclerView
    private lateinit var tvTorrentNewHeader: TextView
    private lateinit var rvTorrentNew: RecyclerView
    private lateinit var tvTorrentSeriesHeader: TextView
    private lateinit var rvTorrentSeries: RecyclerView
    private lateinit var tvFavoritesHeader: TextView
    private lateinit var rvFavorites: RecyclerView
    private lateinit var tvHistoryHeader: TextView
    private lateinit var rvHistory: RecyclerView
    private lateinit var layoutTmdbSearchResults: LinearLayout
    private lateinit var rvTmdbSearchResults: RecyclerView
    private lateinit var rvResults: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var progressSearch: ProgressBar
    private lateinit var tvEmptyMessage: TextView
    private lateinit var layoutLoadingOverlay: FrameLayout
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingStep: TextView
    private lateinit var progressBuffer: ProgressBar
    private lateinit var tvLoadingStats: TextView

    private var searchJob: Job? = null
    private var launchJob: Job? = null
    private var mirrorCheckJob: Job? = null
    private var torrServeManagerJob: Job? = null
    private var tmdbLoadJob: Job? = null
    private var tmdbSearchJob: Job? = null
    private var pendingStorageAction: (() -> Unit)? = null
    private var tmdbLoadedOnce: Boolean = false
    private var tmdbShelves: CatalogShelves? = null
    private var torrentShelves: TorrentShowcaseShelves? = null
    private var tmdbIsLoading: Boolean = false
    private var lastPlayedHash: String = ""
    private var waitingForPlayerReturn: Boolean = false
    private var isSettingsDialogOpen: Boolean = false
    private var updateCheckJob: Job? = null
    private var lastSuccessfulUpdateCheckMs: Long = 0L
    private var lastPromptedUpdateVersion: String = ""
    private var pendingUpdateRelease: UpdateManager.ReleaseInfo? = null

    private var allResults: List<TorrentItem> = emptyList()
    private var lastSourceName: String = ""
    private var currentFilter: QualityFilter = QualityFilter.ALL
    private var currentSort: SortMode = SortMode.SEEDS
    private var hideCamReleases: Boolean = false
    private var onlyLiveSeeds: Boolean = false

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) {
                etSearch.setText(spoken)
                etSearch.setSelection(spoken.length)
                updateFavoriteButtonUi()
                performSearch()
            } else {
                Toast.makeText(this, getString(R.string.voice_no_match), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val action = pendingStorageAction
        pendingStorageAction = null
        action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLogger.init(this)
        storage = StorageManager(this)
        searchEngine = SearchEngine(storage)
        torrServeClient = TorrServeClient(storage)
        torrServeStarter = TorrServeStarter(storage, torrServeClient)
        playerController = PlayerController(this)
        playerPrefs = PlayerPrefs(this)
        maintenanceManager = MaintenanceManager(this)
        updateManager = UpdateManager(this)
        tmdbClient = TmdbClient(storage)
        kinopoiskClient = KinopoiskClient(storage)
        torrentShowcaseClient = TorrentShowcaseClient(storage)
        posterLoader = PosterLoader(this)

        hideCamReleases = storage.hideCamReleases
        onlyLiveSeeds = storage.onlyLiveSeeds

        bindViews()
        setupListeners()
        updateFilterChipsUi()
        updateFavoriteButtonUi()

        updateClearSearchButtonUi()
        refreshHomeShelves()
        loadTmdbShowcase(force = false)
        etSearch.requestFocus()
        refreshTorrServeStatus(autoStart = true, silentOnly = true)
        scheduleAutoUpdateCheck(initialDelayMs = 1_500L)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForPlayerReturn) {
            waitingForPlayerReturn = false
            val hashToStop = lastPlayedHash
            lastPlayedHash = ""
            if (hashToStop.isNotBlank()) {
                uiScope.launch {
                    torrServeClient.stopAndRemoveTorrent(hashToStop)
                }
            }
        }
        if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
            refreshHomeShelves()
        }
        refreshTorrServeStatus(autoStart = false)
        showPendingUpdateIfReady()
        scheduleAutoUpdateCheck(initialDelayMs = 1_200L)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_ASSIST -> {
                    if (layoutLoadingOverlay.visibility != View.VISIBLE) {
                        startVoiceSearch()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSearchRequested(): Boolean {
        if (layoutLoadingOverlay.visibility != View.VISIBLE) {
            startVoiceSearch()
        }
        return true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (layoutLoadingOverlay.visibility == View.VISIBLE) {
            launchJob?.cancel()
            hideLoadingOverlay()
            val hashToDrop = lastPlayedHash
            lastPlayedHash = ""
            if (hashToDrop.isNotBlank()) {
                uiScope.launch { torrServeClient.stopAndRemoveTorrent(hashToDrop) }
            }
            return
        }
        if (layoutFilters.visibility == View.VISIBLE ||
            rvResults.visibility == View.VISIBLE ||
            layoutTmdbSearchResults.visibility == View.VISIBLE
        ) {
            searchJob?.cancel()
            tmdbSearchJob?.cancel()
            allResults = emptyList()
            adapter.submitList(emptyList())
            tmdbSearchAdapter.submitList(emptyList())
            layoutTmdbSearchResults.visibility = View.GONE
            layoutFilters.visibility = View.GONE
            rvResults.visibility = View.GONE
            refreshHomeShelves()
            etSearch.requestFocus()
            return
        }
        super.onBackPressed()
    }

    private fun showPendingUpdateIfReady() {
        val pending = pendingUpdateRelease ?: return
        if (isFinishing || isDestroyed || isSettingsDialogOpen || layoutLoadingOverlay.visibility == View.VISIBLE) {
            return
        }
        pendingUpdateRelease = null
        lastPromptedUpdateVersion = pending.versionLabel
        updateManager.showUpdateDialog(
            activityContext = this,
            release = pending,
            currentVersionName = BuildConfig.VERSION_NAME,
            manualCheck = false
        )
    }

    private fun scheduleAutoUpdateCheck(initialDelayMs: Long) {
        if (!storage.autoCheckUpdates) return
        if (updateCheckJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (lastSuccessfulUpdateCheckMs > 0L && (now - lastSuccessfulUpdateCheckMs) < AUTO_UPDATE_COOLDOWN_MS) {
            return
        }

        updateCheckJob = uiScope.launch {
            delay(initialDelayMs)
            var result = updateManager.checkLatestReleaseDetailed()
            if (result.release == null) {
                // Retry once in case TV Wi-Fi was still waking up from standby
                delay(6_000L)
                if (isFinishing || isDestroyed || !storage.autoCheckUpdates) return@launch
                result = updateManager.checkLatestReleaseDetailed()
            }

            val release = result.release ?: return@launch
            lastSuccessfulUpdateCheckMs = System.currentTimeMillis()

            if (isFinishing || isDestroyed) return@launch
            if (!updateManager.isNewerRelease(release, BuildConfig.VERSION_NAME)) return@launch
            if (release.versionLabel == lastPromptedUpdateVersion) return@launch

            if (isSettingsDialogOpen || layoutLoadingOverlay.visibility == View.VISIBLE) {
                pendingUpdateRelease = release
                Toast.makeText(
                    this@MainActivity,
                    "Доступно обновление TorPlay v${release.versionLabel}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            lastPromptedUpdateVersion = release.versionLabel
            updateManager.showUpdateDialog(
                activityContext = this@MainActivity,
                release = release,
                currentVersionName = BuildConfig.VERSION_NAME,
                manualCheck = false
            )
        }
    }

    private fun bindViews() {
        btnTorrServeStatus = findViewById(R.id.btnTorrServeStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvTorrServeStatus = findViewById(R.id.tvTorrServeStatus)
        btnFavorites = findViewById(R.id.btnFavorites)
        btnHistory = findViewById(R.id.btnHistory)
        btnSettings = findViewById(R.id.btnSettings)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnVoiceSearch = findViewById(R.id.btnVoiceSearch)
        btnSearch = findViewById(R.id.btnSearch)
        rvRecentSearches = findViewById(R.id.rvRecentSearches)
        layoutFilters = findViewById(R.id.layoutFilters)
        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilter4k = findViewById(R.id.btnFilter4k)
        btnFilter1080 = findViewById(R.id.btnFilter1080)
        btnFilter720 = findViewById(R.id.btnFilter720)
        btnFilterHdr = findViewById(R.id.btnFilterHdr)
        btnFilterNoCam = findViewById(R.id.btnFilterNoCam)
        btnFilterLiveSeeds = findViewById(R.id.btnFilterLiveSeeds)
        btnSort = findViewById(R.id.btnSort)
        tvSearchMeta = findViewById(R.id.tvSearchMeta)
        scrollHomeShelves = findViewById(R.id.scrollHomeShelves)
        layoutHomeShelves = findViewById(R.id.layoutHomeShelves)
        layoutTmdbNotice = findViewById(R.id.layoutTmdbNotice)
        progressTmdb = findViewById(R.id.progressTmdb)
        tvTmdbNotice = findViewById(R.id.tvTmdbNotice)
        tvTmdbTrendingHeader = findViewById(R.id.tvTmdbTrendingHeader)
        rvTmdbTrending = findViewById(R.id.rvTmdbTrending)
        tvTmdbNowPlayingHeader = findViewById(R.id.tvTmdbNowPlayingHeader)
        rvTmdbNowPlaying = findViewById(R.id.rvTmdbNowPlaying)
        tvTmdbSeriesHeader = findViewById(R.id.tvTmdbSeriesHeader)
        rvTmdbSeries = findViewById(R.id.rvTmdbSeries)
        tvTorrentTrendingHeader = findViewById(R.id.tvTorrentTrendingHeader)
        rvTorrentTrending = findViewById(R.id.rvTorrentTrending)
        tvTorrentNewHeader = findViewById(R.id.tvTorrentNewHeader)
        rvTorrentNew = findViewById(R.id.rvTorrentNew)
        tvTorrentSeriesHeader = findViewById(R.id.tvTorrentSeriesHeader)
        rvTorrentSeries = findViewById(R.id.rvTorrentSeries)
        tvFavoritesHeader = findViewById(R.id.tvFavoritesHeader)
        rvFavorites = findViewById(R.id.rvFavorites)
        tvHistoryHeader = findViewById(R.id.tvHistoryHeader)
        rvHistory = findViewById(R.id.rvHistory)
        layoutTmdbSearchResults = findViewById(R.id.layoutTmdbSearchResults)
        rvTmdbSearchResults = findViewById(R.id.rvTmdbSearchResults)
        rvResults = findViewById(R.id.rvResults)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressSearch = findViewById(R.id.progressSearch)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        layoutLoadingOverlay = findViewById(R.id.layoutLoadingOverlay)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingStep = findViewById(R.id.tvLoadingStep)
        progressBuffer = findViewById(R.id.progressBuffer)
        tvLoadingStats = findViewById(R.id.tvLoadingStats)

        adapter = TorrentAdapter(
            isFavorite = { item -> storage.isFavoriteTorrent(item) },
            onItemClick = { item -> openTorrent(item) },
            onToggleFavorite = { item -> handleToggleFavoriteTorrent(item) }
        )
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        recentSearchAdapter = RecentSearchAdapter(
            onItemClick = { query ->
                etSearch.setText(query)
                etSearch.setSelection(query.length)
                updateFavoriteButtonUi()
                performSearch()
            },
            onItemMenu = { query ->
                showShelfActionDialog(
                    title = getString(R.string.action_recent_title),
                    subtitle = query,
                    deleteOneLabel = getString(R.string.action_recent_delete_one),
                    clearAllLabel = getString(R.string.action_recent_clear_all),
                    onDeleteOne = {
                        storage.removeRecentSearch(query)
                        refreshHomeShelves()
                        Toast.makeText(this, "Запрос удалён", Toast.LENGTH_SHORT).show()
                    },
                    onClearAll = {
                        storage.clearRecentSearches()
                        refreshHomeShelves()
                        Toast.makeText(this, "Недавние запросы очищены", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
        rvRecentSearches.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRecentSearches.adapter = recentSearchAdapter

        favoriteTorrentAdapter = FavoriteTorrentAdapter(
            onItemClick = { fav ->
                openTorrent(fav.toTorrentItem())
            },
            onItemRemove = { fav ->
                storage.removeFavoriteTorrent(fav.id)
                refreshFavoritesAcrossUi()
                Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
            },
            onItemMenu = { fav ->
                showShelfActionDialog(
                    title = getString(R.string.action_favorite_title),
                    subtitle = fav.title,
                    deleteOneLabel = getString(R.string.action_favorite_delete_one),
                    clearAllLabel = getString(R.string.action_favorite_clear_all),
                    onDeleteOne = {
                        storage.removeFavoriteTorrent(fav.id)
                        refreshFavoritesAcrossUi()
                        Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                    },
                    onClearAll = {
                        storage.clearFavoriteTorrents()
                        refreshFavoritesAcrossUi()
                        Toast.makeText(this, "Избранное очищено", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
        rvFavorites.layoutManager = LinearLayoutManager(this)
        rvFavorites.adapter = favoriteTorrentAdapter

        historyAdapter = HistoryAdapter(
            onItemClick = { entry ->
                val torrentItem = TorrentItem(
                    title = entry.title,
                    magnet = entry.link
                )
                openHistoryEntry(torrentItem, entry)
            },
            onItemMenu = { entry ->
                val label = if (entry.episodeLabel.isNotBlank()) {
                    "${entry.title} — ${entry.episodeLabel}"
                } else {
                    entry.title
                }
                showShelfActionDialog(
                    title = getString(R.string.action_history_title),
                    subtitle = label,
                    deleteOneLabel = getString(R.string.action_history_delete_one),
                    clearAllLabel = getString(R.string.action_history_clear_all),
                    onDeleteOne = {
                        storage.removeWatchHistoryItem(entry.id)
                        refreshHomeShelves()
                        Toast.makeText(this, "Удалено из истории", Toast.LENGTH_SHORT).show()
                    },
                    onClearAll = {
                        storage.clearWatchHistory()
                        refreshHomeShelves()
                        Toast.makeText(this, "История просмотров очищена", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
        rvFavorites.isNestedScrollingEnabled = false
        rvHistory.isNestedScrollingEnabled = false

        tmdbTrendingAdapter = CatalogAdapter(uiScope, tmdbClient, posterLoader) { item -> showMovieDetailsDialog(item) }
        rvTmdbTrending.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTmdbTrending.adapter = tmdbTrendingAdapter

        tmdbNowPlayingAdapter = CatalogAdapter(uiScope, tmdbClient, posterLoader) { item -> showMovieDetailsDialog(item) }
        rvTmdbNowPlaying.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTmdbNowPlaying.adapter = tmdbNowPlayingAdapter

        tmdbSeriesAdapter = CatalogAdapter(uiScope, tmdbClient, posterLoader) { item -> showMovieDetailsDialog(item) }
        rvTmdbSeries.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTmdbSeries.adapter = tmdbSeriesAdapter

        tmdbSearchAdapter = CatalogAdapter(uiScope, tmdbClient, posterLoader, compact = true) { item ->
            showMovieDetailsDialog(item)
        }
        rvTmdbSearchResults.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTmdbSearchResults.adapter = tmdbSearchAdapter

        torrentTrendingAdapter = TorrentShowcaseAdapter(
            isFavorite = { item -> storage.isFavoriteTorrent(item) },
            onItemClick = { item -> openTorrent(item) },
            onItemMenu = { item -> showTorrentShowcaseActionDialog(item) }
        )
        rvTorrentTrending.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTorrentTrending.adapter = torrentTrendingAdapter

        torrentNewAdapter = TorrentShowcaseAdapter(
            isFavorite = { item -> storage.isFavoriteTorrent(item) },
            onItemClick = { item -> openTorrent(item) },
            onItemMenu = { item -> showTorrentShowcaseActionDialog(item) }
        )
        rvTorrentNew.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTorrentNew.adapter = torrentNewAdapter

        torrentSeriesAdapter = TorrentShowcaseAdapter(
            isFavorite = { item -> storage.isFavoriteTorrent(item) },
            onItemClick = { item -> openTorrent(item) },
            onItemMenu = { item -> showTorrentShowcaseActionDialog(item) }
        )
        rvTorrentSeries.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvTorrentSeries.adapter = torrentSeriesAdapter
    }

    private fun setupListeners() {
        btnTorrServeStatus.setOnClickListener {
            refreshTorrServeStatus(autoStart = true)
        }
        btnTorrServeStatus.setOnLongClickListener {
            showTorrServeManagerDialog()
            true
        }
        btnTorrServeStatus.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                showTorrServeManagerDialog()
                true
            } else {
                false
            }
        }

        btnFavorites.setOnClickListener {
            showFavoritesDialog()
        }

        btnHistory.setOnClickListener {
            showHistoryDialog()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
            updateClearSearchButtonUi()
            etSearch.requestFocus()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateClearSearchButtonUi()
            }
        })

        layoutTmdbNotice.setOnClickListener {
            if (storage.activeCatalogRequiresKey() && storage.activeCatalogKey().isBlank()) {
                showSettingsDialog(focusCatalogKey = true)
            } else {
                loadTmdbShowcase(force = true)
            }
        }

        btnVoiceSearch.setOnClickListener {
            startVoiceSearch()
        }

        btnSearch.setOnClickListener {
            performSearch()
        }

        etSearch.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                performSearch()
                true
            } else {
                false
            }
        }

        btnFilterAll.setOnClickListener { applyFilter(QualityFilter.ALL) }
        btnFilter4k.setOnClickListener { applyFilter(QualityFilter.UHD_4K) }
        btnFilter1080.setOnClickListener { applyFilter(QualityFilter.FHD_1080) }
        btnFilter720.setOnClickListener { applyFilter(QualityFilter.HD_720) }
        btnFilterHdr.setOnClickListener { applyFilter(QualityFilter.HDR) }

        btnFilterNoCam.setOnClickListener {
            hideCamReleases = !hideCamReleases
            storage.hideCamReleases = hideCamReleases
            updateFilterChipsUi()
            renderFilteredResults(focusFirst = false)
        }

        btnFilterLiveSeeds.setOnClickListener {
            onlyLiveSeeds = !onlyLiveSeeds
            storage.onlyLiveSeeds = onlyLiveSeeds
            updateFilterChipsUi()
            renderFilteredResults(focusFirst = false)
        }

        btnSort.setOnClickListener {
            currentSort = when (currentSort) {
                SortMode.SEEDS -> SortMode.SIZE
                SortMode.SIZE -> SortMode.DATE
                SortMode.DATE -> SortMode.SEEDS
            }
            btnSort.text = when (currentSort) {
                SortMode.SEEDS -> getString(R.string.sort_seeds)
                SortMode.SIZE -> getString(R.string.sort_size)
                SortMode.DATE -> getString(R.string.sort_date)
            }
            renderFilteredResults(focusFirst = false)
        }
    }

    private fun showShelfActionDialog(
        title: String,
        subtitle: String,
        deleteOneLabel: String,
        clearAllLabel: String,
        onDeleteOne: () -> Unit,
        onClearAll: () -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_shelf_actions)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.46).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvActionDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvActionDialogSubtitle)
        val btnDeleteOne = dialog.findViewById<TextView>(R.id.btnActionDeleteOne)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnActionClearAll)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnActionCancel)

        tvTitle.text = title
        tvSubtitle.text = subtitle
        btnDeleteOne.text = deleteOneLabel
        btnClearAll.text = clearAllLabel

        btnDeleteOne.setOnClickListener {
            dialog.dismiss()
            onDeleteOne()
        }
        btnClearAll.setOnClickListener {
            dialog.dismiss()
            onClearAll()
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        btnDeleteOne.requestFocus()
    }

    private fun updateDpadFocusRoutes() {
        val filtersVisible = layoutFilters.visibility == View.VISIBLE
        val resultsVisible = rvResults.visibility == View.VISIBLE
        val tmdbSearchVisible = layoutTmdbSearchResults.visibility == View.VISIBLE
        val recentVisible = rvRecentSearches.visibility == View.VISIBLE
        val homeVisible = scrollHomeShelves.visibility == View.VISIBLE

        if (filtersVisible) {
            etSearch.nextFocusDownId = R.id.btnFilterAll
            btnClearSearch.nextFocusDownId = R.id.btnFilterAll
            btnVoiceSearch.nextFocusDownId = R.id.btnFilterLiveSeeds
            btnSearch.nextFocusDownId = R.id.btnSort

            val filterDown = when {
                tmdbSearchVisible -> R.id.rvTmdbSearchResults
                resultsVisible -> R.id.rvResults
                else -> View.NO_ID
            }
            btnFilterAll.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterAll
            btnFilter4k.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilter4k
            btnFilter1080.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilter1080
            btnFilter720.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilter720
            btnFilterHdr.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterHdr
            btnFilterNoCam.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterNoCam
            btnFilterLiveSeeds.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterLiveSeeds
            btnSort.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnSort

            if (tmdbSearchVisible) {
                rvTmdbSearchResults.nextFocusUpId = R.id.btnFilterAll
                rvTmdbSearchResults.nextFocusDownId = if (resultsVisible) R.id.rvResults else View.NO_ID
                rvResults.nextFocusUpId = R.id.rvTmdbSearchResults
            } else {
                rvResults.nextFocusUpId = R.id.btnFilterAll
            }
        } else {
            val sections = mutableListOf<View>()
            if (recentVisible) sections.add(rvRecentSearches)
            if (tmdbSearchVisible) sections.add(rvTmdbSearchResults)
            if (homeVisible) {
                if (layoutTmdbNotice.visibility == View.VISIBLE) sections.add(layoutTmdbNotice)
                if (rvTmdbTrending.visibility == View.VISIBLE) sections.add(rvTmdbTrending)
                if (rvTmdbNowPlaying.visibility == View.VISIBLE) sections.add(rvTmdbNowPlaying)
                if (rvTmdbSeries.visibility == View.VISIBLE) sections.add(rvTmdbSeries)
                if (rvTorrentTrending.visibility == View.VISIBLE) sections.add(rvTorrentTrending)
                if (rvTorrentNew.visibility == View.VISIBLE) sections.add(rvTorrentNew)
                if (rvTorrentSeries.visibility == View.VISIBLE) sections.add(rvTorrentSeries)
                if (rvFavorites.visibility == View.VISIBLE) sections.add(rvFavorites)
                if (rvHistory.visibility == View.VISIBLE) sections.add(rvHistory)
            }

            val firstId = sections.firstOrNull()?.id ?: View.NO_ID
            etSearch.nextFocusDownId = if (firstId != View.NO_ID) firstId else R.id.etSearch
            btnClearSearch.nextFocusDownId = if (firstId != View.NO_ID) firstId else R.id.btnClearSearch
            btnVoiceSearch.nextFocusDownId = if (firstId != View.NO_ID) firstId else R.id.btnVoiceSearch
            btnSearch.nextFocusDownId = if (firstId != View.NO_ID) firstId else R.id.btnSearch

            for (idx in sections.indices) {
                val cur = sections[idx]
                cur.nextFocusUpId = if (idx == 0) R.id.etSearch else sections[idx - 1].id
                cur.nextFocusDownId = if (idx + 1 < sections.size) sections[idx + 1].id else View.NO_ID
            }
        }
    }

    private fun updateClearSearchButtonUi() {
        val hasText = etSearch.text?.isNotEmpty() == true
        btnClearSearch.visibility = if (hasText) View.VISIBLE else View.GONE
        if (hasText) {
            etSearch.nextFocusRightId = R.id.btnClearSearch
            btnClearSearch.nextFocusLeftId = R.id.etSearch
            btnClearSearch.nextFocusRightId = R.id.btnVoiceSearch
            btnVoiceSearch.nextFocusLeftId = R.id.btnClearSearch
        } else {
            etSearch.nextFocusRightId = R.id.btnVoiceSearch
            btnVoiceSearch.nextFocusLeftId = R.id.etSearch
        }
    }

    private fun handleToggleFavoriteTorrent(item: TorrentItem) {
        val added = storage.toggleFavoriteTorrent(item)
        refreshFavoritesAcrossUi()
        Toast.makeText(
            this,
            if (added) "★ Добавлено в избранное" else "Удалено из избранного",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshFavoritesAcrossUi() {
        updateFavoriteButtonUi()
        adapter.notifyDataSetChanged()
        torrentTrendingAdapter.notifyDataSetChanged()
        torrentNewAdapter.notifyDataSetChanged()
        torrentSeriesAdapter.notifyDataSetChanged()
        if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
            refreshHomeShelves()
        }
    }

    private fun showTorrentShowcaseActionDialog(item: TorrentItem) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_showcase_actions)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.50).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvShowcaseActionTitle)
        val tvMeta = dialog.findViewById<TextView>(R.id.tvShowcaseActionMeta)
        val btnWatch = dialog.findViewById<TextView>(R.id.btnShowcaseWatch)
        val btnFav = dialog.findViewById<TextView>(R.id.btnShowcaseFavorite)
        val btnFindAll = dialog.findViewById<TextView>(R.id.btnShowcaseFindAll)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnShowcaseCancel)

        tvTitle.text = item.title
        val metaParts = mutableListOf<String>()
        if (item.quality.isNotBlank()) metaParts.add(item.quality)
        val sz = item.formattedSize()
        if (sz != "—") metaParts.add(sz)
        metaParts.add("сиды: ${item.seeds}")
        tvMeta.text = metaParts.joinToString(" • ")

        val isFav = storage.isFavoriteTorrent(item)
        btnFav.text = getString(
            if (isFav) R.string.showcase_action_fav_remove else R.string.showcase_action_fav_add
        )

        btnWatch.setOnClickListener {
            dialog.dismiss()
            openTorrent(item)
        }
        btnFav.setOnClickListener {
            dialog.dismiss()
            handleToggleFavoriteTorrent(item)
        }
        btnFindAll.setOnClickListener {
            dialog.dismiss()
            val cleanQuery = TorrentShowcaseClient.extractCleanSearchQuery(item.title)
            etSearch.setText(cleanQuery)
            etSearch.setSelection(cleanQuery.length)
            performSearch()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        btnWatch.requestFocus()
    }

    private fun showFavoritesDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_saved_list)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.80).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvSavedDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSavedDialogSubtitle)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnSavedClearAll)
        val btnClose = dialog.findViewById<TextView>(R.id.btnSavedClose)
        val rvList = dialog.findViewById<RecyclerView>(R.id.rvSavedList)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvSavedEmpty)

        tvTitle.text = getString(R.string.saved_favorites_title)
        tvEmpty.text = getString(R.string.saved_favorites_empty)

        lateinit var modalAdapter: FavoriteTorrentAdapter

        fun renderModal() {
            val favs = storage.getFavoriteTorrents()
            modalAdapter.submitList(favs)
            if (favs.isNotEmpty()) {
                tvSubtitle.text = "Сохранено раздач: ${favs.size} • OK — смотреть, удержание OK — убрать"
                rvList.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                btnClearAll.visibility = View.VISIBLE
                btnClearAll.nextFocusDownId = R.id.rvSavedList
                btnClose.nextFocusDownId = R.id.rvSavedList
            } else {
                tvSubtitle.text = "Сохранено раздач: 0"
                rvList.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                btnClearAll.visibility = View.GONE
                btnClose.nextFocusDownId = R.id.btnSavedClose
                btnClose.requestFocus()
            }
        }

        modalAdapter = FavoriteTorrentAdapter(
            onItemClick = { fav ->
                dialog.dismiss()
                openTorrent(fav.toTorrentItem())
            },
            onItemRemove = { fav ->
                storage.removeFavoriteTorrent(fav.id)
                refreshFavoritesAcrossUi()
                Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) renderModal()
            }
        )
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = modalAdapter

        btnClearAll.setOnClickListener {
            storage.clearFavoriteTorrents()
            refreshFavoritesAcrossUi()
            Toast.makeText(this, "Избранное очищено", Toast.LENGTH_SHORT).show()
            if (dialog.isShowing) renderModal()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        renderModal()
        dialog.show()
        if (rvList.visibility == View.VISIBLE) {
            rvList.post {
                rvList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rvList.getChildAt(0)?.requestFocus()
            }
        } else {
            btnClose.requestFocus()
        }
    }

    private fun showHistoryDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_saved_list)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.80).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvSavedDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSavedDialogSubtitle)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnSavedClearAll)
        val btnClose = dialog.findViewById<TextView>(R.id.btnSavedClose)
        val rvList = dialog.findViewById<RecyclerView>(R.id.rvSavedList)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvSavedEmpty)

        tvTitle.text = getString(R.string.saved_history_title)
        tvEmpty.text = getString(R.string.saved_history_empty)

        lateinit var modalHistoryAdapter: HistoryAdapter

        fun renderModal() {
            val history = storage.getWatchHistory()
            modalHistoryAdapter.submitList(history)
            if (history.isNotEmpty()) {
                tvSubtitle.text = "Записей: ${history.size} • OK — продолжить просмотр, удержание OK — удалить"
                rvList.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                btnClearAll.visibility = View.VISIBLE
                btnClearAll.nextFocusDownId = R.id.rvSavedList
                btnClose.nextFocusDownId = R.id.rvSavedList
            } else {
                tvSubtitle.text = "Записей: 0"
                rvList.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                btnClearAll.visibility = View.GONE
                btnClose.nextFocusDownId = R.id.btnSavedClose
                btnClose.requestFocus()
            }
        }

        modalHistoryAdapter = HistoryAdapter(
            onItemClick = { entry ->
                dialog.dismiss()
                val torrentItem = TorrentItem(
                    title = entry.title,
                    magnet = entry.link
                )
                openHistoryEntry(torrentItem, entry)
            },
            onItemMenu = { entry ->
                val label = if (entry.episodeLabel.isNotBlank()) {
                    "${entry.title} — ${entry.episodeLabel}"
                } else {
                    entry.title
                }
                showShelfActionDialog(
                    title = getString(R.string.action_history_title),
                    subtitle = label,
                    deleteOneLabel = getString(R.string.action_history_delete_one),
                    clearAllLabel = getString(R.string.action_history_clear_all),
                    onDeleteOne = {
                        storage.removeWatchHistoryItem(entry.id)
                        refreshHomeShelves()
                        Toast.makeText(this, "Удалено из истории", Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) renderModal()
                    },
                    onClearAll = {
                        storage.clearWatchHistory()
                        refreshHomeShelves()
                        Toast.makeText(this, "История просмотров очищена", Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) renderModal()
                    }
                )
            }
        )
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = modalHistoryAdapter

        btnClearAll.setOnClickListener {
            storage.clearWatchHistory()
            refreshHomeShelves()
            Toast.makeText(this, "История просмотров очищена", Toast.LENGTH_SHORT).show()
            if (dialog.isShowing) renderModal()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        renderModal()
        dialog.show()
        if (rvList.visibility == View.VISIBLE) {
            rvList.post {
                rvList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rvList.getChildAt(0)?.requestFocus()
            }
        } else {
            btnClose.requestFocus()
        }
    }

    private fun loadTmdbShowcase(force: Boolean) {
        if (!storage.showTmdbShowcase) {
            tmdbLoadJob?.cancel()
            tmdbIsLoading = false
            tmdbShelves = null
            torrentShelves = null
            tmdbLoadedOnce = false
            if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
                refreshHomeShelves()
            }
            return
        }

        val provider = storage.catalogProvider
        if (storage.activeCatalogRequiresKey() && storage.activeCatalogKey().isBlank()) {
            tmdbLoadJob?.cancel()
            tmdbIsLoading = false
            tmdbShelves = null
            torrentShelves = null
            tmdbLoadedOnce = false
            if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
                refreshHomeShelves()
            }
            return
        }

        val alreadyLoaded = if (provider == StorageManager.CATALOG_PROVIDER_TORRENTS) {
            torrentShelves?.hasAnyItems() == true
        } else {
            tmdbShelves?.hasAnyItems() == true
        }
        if (!force && tmdbLoadedOnce && alreadyLoaded) {
            return
        }

        tmdbLoadJob?.cancel()
        tmdbIsLoading = true
        if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
            refreshHomeShelves()
        }

        val providerName = storage.activeCatalogName()
        tmdbLoadJob = uiScope.launch {
            if (provider == StorageManager.CATALOG_PROVIDER_TORRENTS) {
                val shelves = torrentShowcaseClient.fetchShelves()
                tmdbIsLoading = false
                tmdbLoadedOnce = true
                tmdbShelves = null
                torrentShelves = shelves
                if (!shelves.error.isNullOrBlank()) {
                    AppLogger.w("Catalog", "Ошибка витрины торрентов: ${shelves.error}")
                }
            } else {
                val shelves = if (provider == StorageManager.CATALOG_PROVIDER_KINOPOISK) {
                    kinopoiskClient.fetchShelves()
                } else {
                    tmdbClient.fetchShelves()
                }
                tmdbIsLoading = false
                tmdbLoadedOnce = true
                torrentShelves = null
                tmdbShelves = shelves
                if (!shelves.error.isNullOrBlank()) {
                    AppLogger.w("Catalog", "Ошибка загрузки витрины ($providerName): ${shelves.error}")
                }
            }
            if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
                refreshHomeShelves()
            }
        }
    }

    private fun showMovieDetailsDialog(item: CatalogItem) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_movie_details)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.78).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val ivPoster = dialog.findViewById<ImageView>(R.id.ivDetailsPoster)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvDetailsTitle)
        val tvOriginal = dialog.findViewById<TextView>(R.id.tvDetailsOriginal)
        val tvRating = dialog.findViewById<TextView>(R.id.tvDetailsRating)
        val tvType = dialog.findViewById<TextView>(R.id.tvDetailsType)
        val tvMeta = dialog.findViewById<TextView>(R.id.tvDetailsMeta)
        val tvOverview = dialog.findViewById<TextView>(R.id.tvDetailsOverview)
        val btnFind = dialog.findViewById<TextView>(R.id.btnDetailsFindTorrents)
        val btnFav = dialog.findViewById<TextView>(R.id.btnDetailsFavorite)
        val btnClose = dialog.findViewById<TextView>(R.id.btnDetailsClose)

        tvTitle.text = item.title
        if (item.originalTitle.isNotBlank() && !item.originalTitle.equals(item.title, ignoreCase = true)) {
            tvOriginal.text = item.originalTitle
            tvOriginal.visibility = View.VISIBLE
        } else {
            tvOriginal.visibility = View.GONE
        }

        val ratingStr = item.formattedRating()
        if (ratingStr.isNotEmpty()) {
            tvRating.text = ratingStr
            tvRating.visibility = View.VISIBLE
        } else {
            tvRating.visibility = View.GONE
        }

        tvType.text = getString(if (item.isSeries()) R.string.catalog_type_series else R.string.catalog_type_movie)

        val metaParts = mutableListOf<String>()
        if (item.year.isNotBlank()) metaParts.add(item.year)
        if (item.genreNames.isNotEmpty()) metaParts.add(item.genreNames.joinToString(", "))
        tvMeta.text = metaParts.joinToString(" • ")

        var detailsJob: Job? = null
        val needsKpDetails = item.source == KinopoiskClient.SOURCE_ID && item.overview.isBlank() && item.id > 0
        tvOverview.text = if (needsKpDetails) {
            getString(R.string.catalog_loading_overview)
        } else {
            item.overview.ifBlank { getString(R.string.catalog_no_overview) }
        }

        val posterUrl = tmdbClient.buildPosterUrl(item.posterPath)
        posterLoader.load(uiScope, ivPoster, posterUrl)

        if (needsKpDetails) {
            detailsJob = uiScope.launch {
                val full = kinopoiskClient.fetchFilmDetails(item.id)
                if (!dialog.isShowing) return@launch
                if (full != null) {
                    tvOverview.text = full.overview.ifBlank { getString(R.string.catalog_no_overview) }
                    if (ratingStr.isEmpty()) {
                        val newRating = full.formattedRating()
                        if (newRating.isNotEmpty()) {
                            tvRating.text = newRating
                            tvRating.visibility = View.VISIBLE
                        }
                    }
                    if (metaParts.isEmpty() || (item.genreNames.isEmpty() && full.genreNames.isNotEmpty())) {
                        val updatedMeta = mutableListOf<String>()
                        val yr = item.year.ifBlank { full.year }
                        if (yr.isNotBlank()) updatedMeta.add(yr)
                        val gList = if (full.genreNames.isNotEmpty()) full.genreNames else item.genreNames
                        if (gList.isNotEmpty()) updatedMeta.add(gList.joinToString(", "))
                        tvMeta.text = updatedMeta.joinToString(" • ")
                    }
                } else {
                    tvOverview.text = getString(R.string.catalog_no_overview)
                }
            }
        }

        btnFav.visibility = View.GONE

        btnFind.setOnClickListener {
            dialog.dismiss()
            val query = item.torrentSearchQuery()
            etSearch.setText(query)
            etSearch.setSelection(query.length)
            updateFavoriteButtonUi()
            performSearch(fallbackQueries = item.fallbackSearchQueries())
        }



        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { detailsJob?.cancel() }

        dialog.show()
        btnFind.requestFocus()
    }

    private fun startVoiceSearch() {
        hideKeyboard()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val handler = intent.resolveActivity(packageManager)
        if (handler == null) {
            Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.voice_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateFavoriteButtonUi() {
        val count = storage.getFavoriteTorrents().size
        btnFavorites.text = if (count > 0) "★ Избранное ($count)" else getString(R.string.btn_favorites_header)
    }

    private fun hideAllShowcaseShelves() {
        tvTmdbTrendingHeader.visibility = View.GONE
        rvTmdbTrending.visibility = View.GONE
        tvTmdbNowPlayingHeader.visibility = View.GONE
        rvTmdbNowPlaying.visibility = View.GONE
        tvTmdbSeriesHeader.visibility = View.GONE
        rvTmdbSeries.visibility = View.GONE
        tvTorrentTrendingHeader.visibility = View.GONE
        rvTorrentTrending.visibility = View.GONE
        tvTorrentNewHeader.visibility = View.GONE
        rvTorrentNew.visibility = View.GONE
        tvTorrentSeriesHeader.visibility = View.GONE
        rvTorrentSeries.visibility = View.GONE
    }

    private fun refreshHomeShelves() {
        val recents = storage.getRecentSearches()
        val favs = storage.getFavoriteTorrents()
        val history = storage.getWatchHistory()

        if (recents.isNotEmpty()) {
            rvRecentSearches.visibility = View.VISIBLE
            recentSearchAdapter.submitList(recents)
        } else {
            rvRecentSearches.visibility = View.GONE
        }

        val showcaseEnabled = storage.showTmdbShowcase
        val provider = storage.catalogProvider
        val isTorrentMode = provider == StorageManager.CATALOG_PROVIDER_TORRENTS
        var hasShowcaseContent = false

        hideAllShowcaseShelves()

        if (!showcaseEnabled) {
            layoutTmdbNotice.visibility = View.GONE
        } else if (storage.activeCatalogRequiresKey() && storage.activeCatalogKey().isBlank()) {
            layoutTmdbNotice.visibility = View.VISIBLE
            progressTmdb.visibility = View.GONE
            tvTmdbNotice.text = "Витрина (${storage.activeCatalogName()}): укажите API-ключ в Настройках или переключитесь на «Торренты (без VPN)»"
            hasShowcaseContent = true
        } else if (tmdbIsLoading &&
            ((isTorrentMode && torrentShelves?.hasAnyItems() != true) ||
                (!isTorrentMode && tmdbShelves?.hasAnyItems() != true))
        ) {
            layoutTmdbNotice.visibility = View.VISIBLE
            progressTmdb.visibility = View.VISIBLE
            tvTmdbNotice.text = "Загрузка витрины (${storage.activeCatalogName()})…"
            hasShowcaseContent = true
        } else if (isTorrentMode) {
            val tShelves = torrentShelves
            if (tShelves != null && tShelves.hasAnyItems()) {
                layoutTmdbNotice.visibility = View.GONE
                hasShowcaseContent = true

                if (tShelves.popularMovies.isNotEmpty()) {
                    tvTorrentTrendingHeader.visibility = View.VISIBLE
                    rvTorrentTrending.visibility = View.VISIBLE
                    torrentTrendingAdapter.submitList(tShelves.popularMovies)
                }
                if (tShelves.newestMovies.isNotEmpty()) {
                    tvTorrentNewHeader.visibility = View.VISIBLE
                    rvTorrentNew.visibility = View.VISIBLE
                    torrentNewAdapter.submitList(tShelves.newestMovies)
                }
                if (tShelves.popularSeries.isNotEmpty()) {
                    tvTorrentSeriesHeader.visibility = View.VISIBLE
                    rvTorrentSeries.visibility = View.VISIBLE
                    torrentSeriesAdapter.submitList(tShelves.popularSeries)
                }
            } else if (tShelves != null && !tShelves.error.isNullOrBlank()) {
                layoutTmdbNotice.visibility = View.VISIBLE
                progressTmdb.visibility = View.GONE
                tvTmdbNotice.text = "Витрина торрентов недоступна: ${tShelves.error} • OK — повторить"
                hasShowcaseContent = true
            } else {
                layoutTmdbNotice.visibility = View.GONE
            }
        } else {
            val shelves = tmdbShelves
            if (shelves != null && shelves.hasAnyItems()) {
                layoutTmdbNotice.visibility = View.GONE
                hasShowcaseContent = true

                if (shelves.trending.isNotEmpty()) {
                    tvTmdbTrendingHeader.visibility = View.VISIBLE
                    rvTmdbTrending.visibility = View.VISIBLE
                    tmdbTrendingAdapter.submitList(shelves.trending)
                }
                if (shelves.nowPlaying.isNotEmpty()) {
                    tvTmdbNowPlayingHeader.visibility = View.VISIBLE
                    rvTmdbNowPlaying.visibility = View.VISIBLE
                    tmdbNowPlayingAdapter.submitList(shelves.nowPlaying)
                }
                if (shelves.popularSeries.isNotEmpty()) {
                    tvTmdbSeriesHeader.visibility = View.VISIBLE
                    rvTmdbSeries.visibility = View.VISIBLE
                    tmdbSeriesAdapter.submitList(shelves.popularSeries)
                }
            } else if (shelves != null && !shelves.error.isNullOrBlank()) {
                layoutTmdbNotice.visibility = View.VISIBLE
                progressTmdb.visibility = View.GONE
                tvTmdbNotice.text = "Витрина (${storage.activeCatalogName()}) недоступна: ${shelves.error} • OK — повторить"
                hasShowcaseContent = true
            } else {
                layoutTmdbNotice.visibility = View.GONE
            }
        }

        if (favs.isNotEmpty()) {
            tvFavoritesHeader.visibility = View.VISIBLE
            rvFavorites.visibility = View.VISIBLE
            favoriteTorrentAdapter.submitList(favs)
        } else {
            tvFavoritesHeader.visibility = View.GONE
            rvFavorites.visibility = View.GONE
        }

        if (history.isNotEmpty()) {
            tvHistoryHeader.visibility = View.VISIBLE
            rvHistory.visibility = View.VISIBLE
            historyAdapter.submitList(history)
        } else {
            tvHistoryHeader.visibility = View.GONE
            rvHistory.visibility = View.GONE
        }

        val hasShelves = hasShowcaseContent || favs.isNotEmpty() || history.isNotEmpty()
        scrollHomeShelves.visibility = if (hasShelves) View.VISIBLE else View.GONE
        layoutEmpty.visibility = if (hasShelves) View.GONE else View.VISIBLE
        if (!hasShelves) {
            progressSearch.visibility = View.GONE
            tvEmptyMessage.text = getString(R.string.empty_prompt)
        }
        updateDpadFocusRoutes()
    }

    private fun applyFilter(filter: QualityFilter) {
        currentFilter = filter
        updateFilterChipsUi()
        renderFilteredResults(focusFirst = false)
    }

    private fun updateFilterChipsUi() {
        btnFilterAll.isSelected = currentFilter == QualityFilter.ALL
        btnFilter4k.isSelected = currentFilter == QualityFilter.UHD_4K
        btnFilter1080.isSelected = currentFilter == QualityFilter.FHD_1080
        btnFilter720.isSelected = currentFilter == QualityFilter.HD_720
        btnFilterHdr.isSelected = currentFilter == QualityFilter.HDR
        btnFilterNoCam.isSelected = hideCamReleases
        btnFilterLiveSeeds.isSelected = onlyLiveSeeds
    }

    private fun renderFilteredResults(focusFirst: Boolean) {
        val filtered = allResults.filter { item ->
            val qualityOk = when (currentFilter) {
                QualityFilter.ALL -> true
                QualityFilter.UHD_4K -> item.quality == "4K"
                QualityFilter.FHD_1080 -> item.quality == "1080p"
                QualityFilter.HD_720 -> item.quality == "720p"
                QualityFilter.HDR -> item.isHdr
            }
            val camOk = !hideCamReleases || !item.isCamRelease()
            val seedsOk = !onlyLiveSeeds || item.seeds > 0
            qualityOk && camOk && seedsOk
        }

        val sorted = when (currentSort) {
            SortMode.SEEDS -> filtered.sortedWith(
                compareByDescending<TorrentItem> { it.seeds }
                    .thenByDescending { it.createTime }
                    .thenByDescending { it.sizeBytes }
            )
            SortMode.SIZE -> filtered.sortedWith(
                compareByDescending<TorrentItem> { it.sizeBytes }
                    .thenByDescending { it.seeds }
            )
            SortMode.DATE -> filtered.sortedWith(
                compareByDescending<TorrentItem> { it.createTime }
                    .thenByDescending { it.seeds }
                    .thenByDescending { it.sizeBytes }
            )
        }

        adapter.submitList(sorted)
        tvSearchMeta.text = "Найдено: ${sorted.size}/${allResults.size} • $lastSourceName"

        if (sorted.isNotEmpty()) {
            rvResults.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            updateDpadFocusRoutes()
            if (focusFirst) {
                rvResults.post {
                    rvResults.scrollToPosition(0)
                    rvResults.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        ?: rvResults.getChildAt(0)?.requestFocus()
                }
            }
        } else {
            rvResults.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            progressSearch.visibility = View.GONE
            tvEmptyMessage.text = getString(R.string.no_filtered_results)
            updateDpadFocusRoutes()
        }
    }

    private fun refreshTorrServeStatus(autoStart: Boolean, silentOnly: Boolean = false) {
        uiScope.launch {
            if (autoStart) {
                setStatusIndicator(R.color.status_warn, getString(R.string.status_starting))
            } else {
                setStatusIndicator(R.color.status_warn, getString(R.string.status_checking))
            }

            val version = if (autoStart) {
                torrServeStarter.ensureRunningSmart(
                    context = this@MainActivity,
                    allowUiFallback = !silentOnly
                )
            } else {
                torrServeClient.checkEcho()
            }

            if (version != null) {
                val shortVer = version.take(18)
                setStatusIndicator(R.color.status_ok, "TorrServe: $shortVer")
            } else {
                setStatusIndicator(R.color.status_err, getString(R.string.status_offline))
                if (autoStart && !silentOnly) {
                    Toast.makeText(
                        this@MainActivity,
                        "TorrServe не отвечает (${storage.torrServeUrl}). Убедитесь, что он установлен.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setStatusIndicator(colorRes: Int, label: String) {
        viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        tvTorrServeStatus.text = label
    }

    private fun performSearch(fallbackQueries: List<String> = emptyList()) {
        val query = etSearch.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Введите название фильма", Toast.LENGTH_SHORT).show()
            etSearch.requestFocus()
            return
        }

        hideKeyboard()
        storage.lastQuery = query
        storage.addRecentSearch(query)
        updateFavoriteButtonUi()

        searchJob?.cancel()
        tmdbSearchJob?.cancel()
        tmdbSearchAdapter.submitList(emptyList())
        layoutTmdbSearchResults.visibility = View.GONE

        if (storage.showTmdbShowcase && storage.activeCatalogRequiresKey() && storage.activeCatalogKey().isNotBlank()) {
            val useKinopoisk = storage.catalogProvider == StorageManager.CATALOG_PROVIDER_KINOPOISK
            tmdbSearchJob = uiScope.launch {
                val catalogMatches = if (useKinopoisk) {
                    kinopoiskClient.searchMovies(query)
                } else {
                    tmdbClient.searchMulti(query)
                }
                if (scrollHomeShelves.visibility == View.VISIBLE) return@launch
                if (catalogMatches.isNotEmpty()) {
                    tmdbSearchAdapter.submitList(catalogMatches)
                    layoutTmdbSearchResults.visibility = View.VISIBLE
                    updateDpadFocusRoutes()
                }
            }
        }

        searchJob = uiScope.launch {
            allResults = emptyList()
            currentFilter = QualityFilter.ALL
            updateFilterChipsUi()
            adapter.submitList(emptyList())
            rvRecentSearches.visibility = View.GONE
            layoutFilters.visibility = View.GONE
            scrollHomeShelves.visibility = View.GONE
            rvResults.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            progressSearch.visibility = View.VISIBLE
            tvEmptyMessage.text = getString(R.string.searching)
            updateDpadFocusRoutes()

            var response = searchEngine.search(query)
            var matchedQuery = query

            if (response.items.isEmpty()) {
                val retryList = LinkedHashSet<String>()
                for (fq in fallbackQueries) {
                    val clean = fq.trim()
                    if (clean.isNotBlank() && !clean.equals(query, ignoreCase = true)) {
                        retryList.add(clean)
                    }
                }
                val withoutYear = TRAILING_YEAR_REGEX.replace(query, "").trim()
                if (withoutYear.length >= 2 && !withoutYear.equals(query, ignoreCase = true)) {
                    retryList.add(withoutYear)
                }

                for (altQuery in retryList) {
                    tvEmptyMessage.text = "Уточняем поиск: $altQuery…"
                    val altResp = searchEngine.search(altQuery)
                    if (altResp.items.isNotEmpty()) {
                        response = altResp
                        matchedQuery = altQuery
                        break
                    }
                }
            }

            progressSearch.visibility = View.GONE

            if (response.items.isNotEmpty()) {
                if (!matchedQuery.equals(query, ignoreCase = true)) {
                    etSearch.setText(matchedQuery)
                    etSearch.setSelection(matchedQuery.length)
                    Toast.makeText(
                        this@MainActivity,
                        "Показаны раздачи по запросу «$matchedQuery»",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                allResults = response.items
                lastSourceName = response.sourceName
                scrollHomeShelves.visibility = View.GONE
                layoutFilters.visibility = View.VISIBLE
                renderFilteredResults(focusFirst = true)
            } else {
                layoutFilters.visibility = View.GONE
                scrollHomeShelves.visibility = View.GONE
                rvResults.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
                val rawErr = response.error.orEmpty()
                if (rawErr.isNotBlank()) {
                    AppLogger.w("Search", "Запрос '$query': $rawErr")
                }
                val isBlockedRutorFallback = storage.usePublicJacRed &&
                    (rawErr.contains("rutor", ignoreCase = true) ||
                        rawErr.contains("Unable to resolve host", ignoreCase = true))
                tvEmptyMessage.text = when {
                    rawErr.isBlank() || isBlockedRutorFallback ->
                        "Раздачи по запросу «$query» не найдены"
                    else -> rawErr
                }
                updateDpadFocusRoutes()
            }
        }
    }

    private fun openTorrent(item: TorrentItem, forcedFileId: Int? = null, forcedEpisodeLabel: String = "") {
        val link = item.playableLink
        if (link.isBlank()) {
            Toast.makeText(this, "У раздачи нет magnet-ссылки", Toast.LENGTH_SHORT).show()
            return
        }

        launchJob?.cancel()
        launchJob = uiScope.launch {
            showLoadingOverlay(item.title, "Проверка TorrServe…", showBuffer = false)

            val version = torrServeStarter.ensureRunningSmart(this@MainActivity, allowUiFallback = true)
            if (version == null) {
                hideLoadingOverlay()
                setStatusIndicator(R.color.status_err, getString(R.string.status_offline))
                Toast.makeText(
                    this@MainActivity,
                    "TorrServe недоступен. Запустите TorrServe MatriX или проверьте адрес в настройках.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            setStatusIndicator(R.color.status_ok, "TorrServe: ${version.take(18)}")

            if (lastPlayedHash.isNotBlank()) {
                torrServeClient.stopAndRemoveTorrent(lastPlayedHash)
                lastPlayedHash = ""
            }

            tvLoadingStep.text = "Добавление раздачи в TorrServe…"
            val added = runCatching { torrServeClient.addTorrent(link, item.title) }
                .onFailure {
                    AppLogger.e("TorrServe", "Ошибка добавления раздачи (${item.title})", it)
                    hideLoadingOverlay()
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка TorrServe: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .getOrNull() ?: return@launch

            val hash = added.hash.ifBlank { link }
            if (added.hash.isNotBlank()) {
                lastPlayedHash = added.hash
            }

            tvLoadingStep.text = "Получение списка видеофайлов…"
            val files = if (added.files.isNotEmpty()) {
                val vids = torrServeClient.filterAndSortVideos(added.files)
                if (vids.isNotEmpty()) vids else added.files
            } else {
                torrServeClient.waitForVideoFiles(hash, timeoutMs = 14_000L)
            }

            if (forcedFileId != null) {
                val matched = files.firstOrNull { it.id == forcedFileId }
                    ?: TorrServeFile(id = forcedFileId, path = forcedEpisodeLabel.ifBlank { "${item.title}.mkv" }, length = 0L)
                bufferAndLaunchFile(
                    hash = hash,
                    link = link,
                    displayTitle = item.title,
                    episodeLabel = forcedEpisodeLabel.ifBlank { matched.fileName },
                    file = matched
                )
            } else if (files.size > 1) {
                hideLoadingOverlay()
                showEpisodePickerDialog(item.title, hash, link, files)
            } else {
                val file = files.firstOrNull()
                    ?: TorrServeFile(id = 1, path = "${item.title}.mkv", length = 0L)
                bufferAndLaunchFile(
                    hash = hash,
                    link = link,
                    displayTitle = item.title,
                    episodeLabel = "",
                    file = file
                )
            }
        }
    }

    private fun openHistoryEntry(item: TorrentItem, entry: WatchHistoryEntry) {
        openTorrent(
            item = item,
            forcedFileId = entry.fileId,
            forcedEpisodeLabel = entry.episodeLabel
        )
    }

    private fun showEpisodePickerDialog(
        torrentTitle: String,
        hash: String,
        link: String,
        files: List<TorrServeFile>
    ) {
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

        tvHeader.text = torrentTitle
        fun refreshEpisodeHeader() {
            val watchedCount = files.count { storage.isEpisodeCompleted(hash, it.id) }
            tvCount.text = "Серий: ${files.size} (просмотрено: $watchedCount) • OK — смотреть • Удерж. OK — отметка"
        }
        refreshEpisodeHeader()

        lateinit var episodeAdapter: SmartEpisodeAdapter
        episodeAdapter = SmartEpisodeAdapter(
            files = files,
            currentPlayingFileId = null,
            lastOpenedFileId = storage.getLastEpisodeForTorrent(hash),
            progressProvider = { fileId -> storage.getEpisodeProgress(hash, fileId) },
            completedChecker = { fileId -> storage.isEpisodeCompleted(hash, fileId) },
            onEpisodeClick = { selectedFile ->
                dialog.dismiss()
                launchJob?.cancel()
                launchJob = uiScope.launch {
                    bufferAndLaunchFile(
                        hash = hash,
                        link = link,
                        displayTitle = torrentTitle,
                        episodeLabel = selectedFile.fileName,
                        file = selectedFile
                    )
                }
            },
            onEpisodeToggleWatched = { selectedFile ->
                val nowWatched = storage.toggleEpisodeWatched(hash, selectedFile.id)
                episodeAdapter.notifyDataSetChanged()
                refreshEpisodeHeader()
                Toast.makeText(
                    this,
                    if (nowWatched) "✓ Отмечено как просмотренное" else "Отметка просмотра снята",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        val lm = LinearLayoutManager(this)
        rvEpisodes.layoutManager = lm
        rvEpisodes.adapter = episodeAdapter

        dialog.setOnCancelListener {
            if (lastPlayedHash.isNotBlank()) {
                val h = lastPlayedHash
                lastPlayedHash = ""
                uiScope.launch { torrServeClient.stopAndRemoveTorrent(h) }
            }
        }

        dialog.show()

        val targetIdx = storage.resolveResumeEpisodeIndex(hash, files).coerceIn(0, files.lastIndex)
        val topOffset = (72 * resources.displayMetrics.density).toInt()
        lm.scrollToPositionWithOffset(targetIdx, topOffset)
        rvEpisodes.post {
            val vh = rvEpisodes.findViewHolderForAdapterPosition(targetIdx)
            if (vh != null) {
                vh.itemView.requestFocus()
            } else {
                lm.scrollToPositionWithOffset(targetIdx, topOffset)
                rvEpisodes.post {
                    rvEpisodes.findViewHolderForAdapterPosition(targetIdx)?.itemView?.requestFocus()
                        ?: rvEpisodes.getChildAt(0)?.requestFocus()
                }
            }
        }
    }

    private suspend fun bufferAndLaunchFile(
        hash: String,
        link: String,
        displayTitle: String,
        episodeLabel: String,
        file: TorrServeFile
    ) {
        val fullTitle = if (episodeLabel.isNotBlank()) "$displayTitle — $episodeLabel" else displayTitle
        showLoadingOverlay(fullTitle, "Буферизация потока…", showBuffer = true)
        progressBuffer.progress = 0
        tvLoadingStats.text = "Поиск пиров…"

        torrServeClient.preloadWithProgress(hash, file.id) { info ->
            val pct = info.preloadPercent()
            progressBuffer.progress = pct
            tvLoadingStep.text = if (info.statString.isNotBlank()) info.statString else "Буферизация…"
            tvLoadingStats.text = "Буфер: $pct%  •  ${info.formattedSpeed()}  •  Пиры: ${info.activePeers}/${info.totalPeers} (сиды: ${info.connectedSeeders})"
        }

        val streamUrl = torrServeClient.buildStreamUrl(hash, file.id, file.fileName)
        val savedPos = storage.getSavedPositionMs(link, file.id, hash)

        val launchResult = if (storage.useBuiltInPlayer) {
            tvLoadingStep.text = "Запуск встроенного плеера…"
            playerController.playBuiltIn(
                streamUrl = streamUrl,
                title = displayTitle,
                episodeLabel = episodeLabel,
                link = link,
                fileId = file.id,
                startPositionMs = savedPos
            )
        } else {
            tvLoadingStep.text = "Запуск MX Player…"
            playerController.playExternal(streamUrl, fullTitle)
        }

        hideLoadingOverlay()

        if (launchResult.launched) {
            storage.setLastEpisodeForTorrent(hash, file.id)
            if (!storage.useBuiltInPlayer) {
                storage.markEpisodeWatched(hash, file.id)
                storage.saveWatchPosition(
                    title = displayTitle,
                    link = link,
                    fileId = file.id,
                    episodeLabel = episodeLabel,
                    positionMs = 0L,
                    durationMs = 0L
                )
            }
            waitingForPlayerReturn = true
        } else {
            Toast.makeText(
                this@MainActivity,
                launchResult.error ?: "Не удалось открыть плеер",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showLoadingOverlay(title: String, step: String, showBuffer: Boolean) {
        tvLoadingTitle.text = title
        tvLoadingStep.text = step
        progressBuffer.visibility = if (showBuffer) View.VISIBLE else View.GONE
        tvLoadingStats.visibility = if (showBuffer) View.VISIBLE else View.GONE
        layoutLoadingOverlay.visibility = View.VISIBLE
        layoutLoadingOverlay.requestFocus()
    }

    private fun hideLoadingOverlay() {
        layoutLoadingOverlay.visibility = View.GONE
    }

    private fun runWithStoragePermission(action: () -> Unit) {
        if (maintenanceManager.needsLegacyStoragePermission()) {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    private fun showTorrServeManagerDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_torrserve_manager)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.80).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvTsManagerSubtitle)
        val btnRefresh = dialog.findViewById<TextView>(R.id.btnTsRefresh)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnTsClearAll)
        val btnClose = dialog.findViewById<TextView>(R.id.btnTsClose)
        val rvTorrents = dialog.findViewById<RecyclerView>(R.id.rvTsTorrents)
        val layoutEmpty = dialog.findViewById<LinearLayout>(R.id.layoutTsEmpty)
        val progressLoading = dialog.findViewById<ProgressBar>(R.id.progressTsLoading)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvTsEmpty)

        lateinit var tsAdapter: TorrServeAdapter

        fun loadList() {
            torrServeManagerJob?.cancel()
            rvTorrents.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            progressLoading.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.ts_manager_loading)
            tvSubtitle.text = getString(R.string.ts_manager_loading)

            torrServeManagerJob = uiScope.launch {
                val res = runCatching { torrServeClient.listTorrents() }
                if (!dialog.isShowing) return@launch
                progressLoading.visibility = View.GONE

                res.onSuccess { list ->
                    if (list.isNotEmpty()) {
                        tsAdapter.submitList(list)
                        tvSubtitle.text = "Активных раздач в памяти: ${list.size}"
                        layoutEmpty.visibility = View.GONE
                        rvTorrents.visibility = View.VISIBLE
                        btnRefresh.nextFocusDownId = R.id.rvTsTorrents
                        btnClearAll.nextFocusDownId = R.id.rvTsTorrents
                        btnClose.nextFocusDownId = R.id.rvTsTorrents
                        rvTorrents.post {
                            rvTorrents.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                ?: rvTorrents.getChildAt(0)?.requestFocus()
                        }
                    } else {
                        tsAdapter.submitList(emptyList())
                        tvSubtitle.text = "Активных раздач: 0"
                        rvTorrents.visibility = View.GONE
                        layoutEmpty.visibility = View.VISIBLE
                        tvEmpty.text = getString(R.string.ts_manager_empty)
                        btnRefresh.nextFocusDownId = R.id.btnTsRefresh
                        btnClearAll.nextFocusDownId = R.id.btnTsClearAll
                        btnClose.nextFocusDownId = R.id.btnTsClose
                        btnClose.requestFocus()
                    }
                }.onFailure { err ->
                    AppLogger.e("TorrServe", "Ошибка получения списка раздач", err)
                    tvSubtitle.text = "Нет связи (${storage.torrServeUrl})"
                    rvTorrents.visibility = View.GONE
                    layoutEmpty.visibility = View.VISIBLE
                    tvEmpty.text = getString(R.string.ts_manager_offline)
                    btnRefresh.nextFocusDownId = R.id.btnTsRefresh
                    btnClearAll.nextFocusDownId = R.id.btnTsClearAll
                    btnClose.nextFocusDownId = R.id.btnTsClose
                    btnRefresh.requestFocus()
                }
            }
        }

        tsAdapter = TorrServeAdapter { item ->
            torrServeManagerJob?.cancel()
            torrServeManagerJob = uiScope.launch {
                torrServeClient.stopAndRemoveTorrent(item.hash)
                if (lastPlayedHash == item.hash) {
                    lastPlayedHash = ""
                }
                AppLogger.i("TorrServe", "Удалена раздача: ${item.title.ifBlank { item.hash }}")
                Toast.makeText(this@MainActivity, "Раздача остановлена и удалена", Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) loadList()
            }
        }

        rvTorrents.layoutManager = LinearLayoutManager(this)
        rvTorrents.adapter = tsAdapter

        btnRefresh.setOnClickListener { loadList() }
        btnClearAll.setOnClickListener {
            torrServeManagerJob?.cancel()
            torrServeManagerJob = uiScope.launch {
                progressLoading.visibility = View.VISIBLE
                tvSubtitle.text = "Очистка TorrServe…"
                val count = runCatching { torrServeClient.clearAllTorrents() }
                    .onFailure { AppLogger.e("TorrServe", "Ошибка очистки всех раздач", it) }
                    .getOrDefault(0)
                lastPlayedHash = ""
                AppLogger.i("TorrServe", "Очистка всех раздач: удалено $count")
                Toast.makeText(
                    this@MainActivity,
                    if (count > 0) "Остановлено и удалено раздач: $count" else "Нечего очищать",
                    Toast.LENGTH_SHORT
                ).show()
                if (dialog.isShowing) loadList()
            }
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            torrServeManagerJob?.cancel()
        }

        dialog.show()
        btnRefresh.requestFocus()
        loadList()
    }

    private fun showSettingsDialog(focusCatalogKey: Boolean = false) {
        hideKeyboard()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.80).toInt(),
            (resources.displayMetrics.heightPixels * 0.88).toInt()
        )

        val scrollSettings = dialog.findViewById<ScrollView>(R.id.scrollSettings)
        val tvVersion = dialog.findViewById<TextView>(R.id.tvSettingsVersion)
        val btnCheckUpdates = dialog.findViewById<TextView>(R.id.btnCheckUpdates)
        val btnPlayerBuiltin = dialog.findViewById<TextView>(R.id.btnPlayerBuiltin)
        val btnPlayerExternal = dialog.findViewById<TextView>(R.id.btnPlayerExternal)
        val btnBufferFast = dialog.findViewById<TextView>(R.id.btnBufferFast)
        val btnBufferStandard = dialog.findViewById<TextView>(R.id.btnBufferStandard)
        val btnBuffer4k = dialog.findViewById<TextView>(R.id.btnBuffer4k)
        val btnTogglePublicJacRed = dialog.findViewById<TextView>(R.id.btnTogglePublicJacRed)
        val btnToggleRutorFallback = dialog.findViewById<TextView>(R.id.btnToggleRutorFallback)
        val btnToggleAutoUpdate = dialog.findViewById<TextView>(R.id.btnToggleAutoUpdate)
        val etTorrServe = dialog.findViewById<EditText>(R.id.etTorrServeUrl)
        val etCustomParser = dialog.findViewById<EditText>(R.id.etCustomParserUrl)
        val etApiKey = dialog.findViewById<EditText>(R.id.etApiKey)
        val etRutorMirror = dialog.findViewById<EditText>(R.id.etRutorMirror)
        val btnToggleTmdbShowcase = dialog.findViewById<TextView>(R.id.btnToggleTmdbShowcase)
        val btnCatalogTorrents = dialog.findViewById<TextView>(R.id.btnCatalogTorrents)
        val btnCatalogKinopoisk = dialog.findViewById<TextView>(R.id.btnCatalogKinopoisk)
        val btnCatalogTmdb = dialog.findViewById<TextView>(R.id.btnCatalogTmdb)
        val etKinopoiskApiKey = dialog.findViewById<EditText>(R.id.etKinopoiskApiKey)
        val etTmdbApiKey = dialog.findViewById<EditText>(R.id.etTmdbApiKey)
        val etTmdbApiUrl = dialog.findViewById<EditText>(R.id.etTmdbApiUrl)
        val etTmdbImageUrl = dialog.findViewById<EditText>(R.id.etTmdbImageUrl)
        val btnTorrServeManager = dialog.findViewById<TextView>(R.id.btnTorrServeManager)
        val btnBackupExport = dialog.findViewById<TextView>(R.id.btnBackupExport)
        val btnBackupImport = dialog.findViewById<TextView>(R.id.btnBackupImport)
        val btnExportLogs = dialog.findViewById<TextView>(R.id.btnExportLogs)
        val layoutMirrorResults = dialog.findViewById<LinearLayout>(R.id.layoutMirrorResults)
        val progressMirrorCheck = dialog.findViewById<ProgressBar>(R.id.progressMirrorCheck)
        val tvMirrorCheckStatus = dialog.findViewById<TextView>(R.id.tvMirrorCheckStatus)
        val layoutMirrorList = dialog.findViewById<LinearLayout>(R.id.layoutMirrorList)
        val btnCheckMirrors = dialog.findViewById<TextView>(R.id.btnCheckMirrors)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnCancelSettings)
        val btnSave = dialog.findViewById<TextView>(R.id.btnSaveSettings)

        tvVersion.text = "Версия ${BuildConfig.VERSION_NAME}"

        var selectedBuiltIn = storage.useBuiltInPlayer
        var selectedBufferProfile = storage.bufferProfile
        var publicJacRedEnabled = storage.usePublicJacRed
        var rutorFallbackEnabled = storage.useRutorFallback
        var autoCheckUpdatesEnabled = storage.autoCheckUpdates
        var tmdbShowcaseEnabled = storage.showTmdbShowcase
        var selectedCatalogProvider = storage.catalogProvider

        fun refreshPlayerChips() {
            btnPlayerBuiltin.isSelected = selectedBuiltIn
            btnPlayerExternal.isSelected = !selectedBuiltIn
        }

        fun refreshBufferChips() {
            btnBufferFast.isSelected = selectedBufferProfile == StorageManager.BUFFER_PROFILE_FAST
            btnBufferStandard.isSelected = selectedBufferProfile == StorageManager.BUFFER_PROFILE_STANDARD
            btnBuffer4k.isSelected = selectedBufferProfile == StorageManager.BUFFER_PROFILE_4K
        }

        fun refreshSourceChips() {
            btnTogglePublicJacRed.isSelected = publicJacRedEnabled
            btnTogglePublicJacRed.text = getString(
                if (publicJacRedEnabled) R.string.source_jacred_on else R.string.source_jacred_off
            )
            btnToggleRutorFallback.isSelected = rutorFallbackEnabled
            btnToggleRutorFallback.text = getString(
                if (rutorFallbackEnabled) R.string.source_rutor_on else R.string.source_rutor_off
            )
            btnToggleAutoUpdate.isSelected = autoCheckUpdatesEnabled
            btnToggleAutoUpdate.text = getString(
                if (autoCheckUpdatesEnabled) R.string.auto_update_on else R.string.auto_update_off
            )
            btnToggleTmdbShowcase.isSelected = tmdbShowcaseEnabled
            btnToggleTmdbShowcase.text = getString(
                if (tmdbShowcaseEnabled) R.string.tmdb_showcase_on else R.string.tmdb_showcase_off
            )
            btnCatalogTorrents.isSelected = selectedCatalogProvider == StorageManager.CATALOG_PROVIDER_TORRENTS
            btnCatalogKinopoisk.isSelected = selectedCatalogProvider == StorageManager.CATALOG_PROVIDER_KINOPOISK
            btnCatalogTmdb.isSelected = selectedCatalogProvider == StorageManager.CATALOG_PROVIDER_TMDB
        }

        fun populateFieldsFromStorage() {
            selectedBuiltIn = storage.useBuiltInPlayer
            selectedBufferProfile = storage.bufferProfile
            publicJacRedEnabled = storage.usePublicJacRed
            rutorFallbackEnabled = storage.useRutorFallback
            autoCheckUpdatesEnabled = storage.autoCheckUpdates
            etTorrServe.setText(storage.torrServeUrl)
            etCustomParser.setText(storage.customParserUrl)
            etApiKey.setText(storage.parserApiKey)
            tmdbShowcaseEnabled = storage.showTmdbShowcase
            selectedCatalogProvider = storage.catalogProvider
            etRutorMirror.setText(storage.rutorMirrorUrl)
            etKinopoiskApiKey.setText(storage.kinopoiskApiKey)
            etTmdbApiKey.setText(storage.tmdbApiKey)
            etTmdbApiUrl.setText(storage.tmdbApiBaseUrl)
            etTmdbImageUrl.setText(storage.tmdbImageBaseUrl)
            refreshPlayerChips()
            refreshBufferChips()
            refreshSourceChips()
        }

        populateFieldsFromStorage()

        val settingsInputs = listOf(
            etTorrServe,
            etCustomParser,
            etApiKey,
            etRutorMirror,
            etKinopoiskApiKey,
            etTmdbApiKey,
            etTmdbApiUrl,
            etTmdbImageUrl
        )

        fun hideDialogKeyboard() {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val token = dialog.currentFocus?.windowToken ?: dialog.window?.decorView?.windowToken
            if (token != null) {
                imm?.hideSoftInputFromWindow(token, 0)
            }
            for (input in settingsInputs) {
                if (input.hasFocus()) {
                    input.clearFocus()
                }
            }
        }

        for (input in settingsInputs) {
            input.setOnEditorActionListener { _, actionId, event ->
                val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    (event != null &&
                        event.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER))
                if (isDone) {
                    hideDialogKeyboard()
                    true
                } else {
                    false
                }
            }
        }

        scrollSettings.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE && settingsInputs.any { it.hasFocus() }) {
                hideDialogKeyboard()
            }
            false
        }

        btnPlayerBuiltin.setOnClickListener {
            hideDialogKeyboard()
            selectedBuiltIn = true
            refreshPlayerChips()
        }
        btnPlayerExternal.setOnClickListener {
            hideDialogKeyboard()
            selectedBuiltIn = false
            refreshPlayerChips()
        }
        btnBufferFast.setOnClickListener {
            hideDialogKeyboard()
            selectedBufferProfile = StorageManager.BUFFER_PROFILE_FAST
            refreshBufferChips()
        }
        btnBufferStandard.setOnClickListener {
            hideDialogKeyboard()
            selectedBufferProfile = StorageManager.BUFFER_PROFILE_STANDARD
            refreshBufferChips()
        }
        btnBuffer4k.setOnClickListener {
            hideDialogKeyboard()
            selectedBufferProfile = StorageManager.BUFFER_PROFILE_4K
            refreshBufferChips()
        }
        btnTogglePublicJacRed.setOnClickListener {
            hideDialogKeyboard()
            publicJacRedEnabled = !publicJacRedEnabled
            refreshSourceChips()
        }
        btnToggleRutorFallback.setOnClickListener {
            hideDialogKeyboard()
            rutorFallbackEnabled = !rutorFallbackEnabled
            refreshSourceChips()
        }
        btnToggleAutoUpdate.setOnClickListener {
            hideDialogKeyboard()
            autoCheckUpdatesEnabled = !autoCheckUpdatesEnabled
            refreshSourceChips()
        }
        btnToggleTmdbShowcase.setOnClickListener {
            hideDialogKeyboard()
            tmdbShowcaseEnabled = !tmdbShowcaseEnabled
            refreshSourceChips()
        }
        btnCatalogTorrents.setOnClickListener {
            hideDialogKeyboard()
            selectedCatalogProvider = StorageManager.CATALOG_PROVIDER_TORRENTS
            refreshSourceChips()
        }
        btnCatalogKinopoisk.setOnClickListener {
            hideDialogKeyboard()
            selectedCatalogProvider = StorageManager.CATALOG_PROVIDER_KINOPOISK
            refreshSourceChips()
        }
        btnCatalogTmdb.setOnClickListener {
            hideDialogKeyboard()
            selectedCatalogProvider = StorageManager.CATALOG_PROVIDER_TMDB
            refreshSourceChips()
        }

        btnTorrServeManager.setOnClickListener {
            hideDialogKeyboard()
            storage.torrServeUrl = etTorrServe.text.toString()
            showTorrServeManagerDialog()
        }

        btnBackupExport.setOnClickListener {
            hideDialogKeyboard()
            storage.useBuiltInPlayer = selectedBuiltIn
            storage.bufferProfile = selectedBufferProfile
            storage.usePublicJacRed = publicJacRedEnabled
            storage.useRutorFallback = rutorFallbackEnabled
            storage.autoCheckUpdates = autoCheckUpdatesEnabled
            storage.torrServeUrl = etTorrServe.text.toString()
            storage.customParserUrl = etCustomParser.text.toString()
            storage.parserApiKey = etApiKey.text.toString()
            storage.rutorMirrorUrl = etRutorMirror.text.toString()
            storage.showTmdbShowcase = tmdbShowcaseEnabled
            storage.catalogProvider = selectedCatalogProvider
            storage.kinopoiskApiKey = etKinopoiskApiKey.text.toString()
            storage.tmdbApiKey = etTmdbApiKey.text.toString()
            storage.tmdbApiBaseUrl = etTmdbApiUrl.text.toString()
            storage.tmdbImageBaseUrl = etTmdbImageUrl.text.toString()
            runWithStoragePermission {
                val res = maintenanceManager.exportBackup(storage, playerPrefs)
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }

        btnBackupImport.setOnClickListener {
            hideDialogKeyboard()
            runWithStoragePermission {
                val res = maintenanceManager.importBackup(storage, playerPrefs)
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
                if (res.ok) {
                    hideCamReleases = storage.hideCamReleases
                    onlyLiveSeeds = storage.onlyLiveSeeds
                    updateFilterChipsUi()
                    updateFavoriteButtonUi()
                    if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
                        refreshHomeShelves()
                    }
                    if (dialog.isShowing) {
                        populateFieldsFromStorage()
                    }
                    loadTmdbShowcase(force = true)
                    refreshTorrServeStatus(autoStart = false)
                }
            }
        }

        btnExportLogs.setOnClickListener {
            hideDialogKeyboard()
            runWithStoragePermission {
                val res = maintenanceManager.exportLogs(storage)
                Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }

        btnCheckUpdates.setOnClickListener {
            hideDialogKeyboard()
            Toast.makeText(this, "Проверяем релизы на GitHub…", Toast.LENGTH_SHORT).show()
            uiScope.launch {
                val result = updateManager.checkLatestReleaseDetailed()
                val release = result.release
                if (release != null) {
                    lastSuccessfulUpdateCheckMs = System.currentTimeMillis()
                    updateManager.showUpdateDialog(
                        activityContext = this@MainActivity,
                        release = release,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        manualCheck = true
                    )
                } else {
                    val detail = result.error?.let { " ($it)" }.orEmpty()
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось проверить GitHub$detail",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        btnCheckMirrors.setOnClickListener {
            hideDialogKeyboard()
            mirrorCheckJob?.cancel()
            layoutMirrorResults.visibility = View.VISIBLE
            progressMirrorCheck.visibility = View.VISIBLE
            tvMirrorCheckStatus.text = getString(R.string.checking_mirrors)
            layoutMirrorList.removeAllViews()

            val customUrlInput = etCustomParser.text.toString()
            val apiKeyInput = etApiKey.text.toString()
            val rutorInput = etRutorMirror.text.toString()
            val kpKeyInput = etKinopoiskApiKey.text.toString()
            val tmdbKeyInput = etTmdbApiKey.text.toString()
            val tmdbApiInput = etTmdbApiUrl.text.toString()

            mirrorCheckJob = uiScope.launch {
                val results = searchEngine.checkMirrors(
                    customUrl = customUrlInput,
                    apiKey = apiKeyInput,
                    rutorUrl = rutorInput
                ).toMutableList()
                if (kpKeyInput.isNotBlank()) {
                    results.add(kinopoiskClient.probeConnection(kpKeyInput))
                }
                if (tmdbKeyInput.isNotBlank()) {
                    results.add(tmdbClient.probeConnection(tmdbKeyInput, tmdbApiInput))
                }
                if (!dialog.isShowing) return@launch

                progressMirrorCheck.visibility = View.GONE
                val okCount = results.count { it.ok }
                tvMirrorCheckStatus.text = "Проверка зеркал: доступно $okCount из ${results.size}"

                layoutMirrorList.removeAllViews()
                val dp4 = (4 * resources.displayMetrics.density).toInt()
                for (item in results) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp4, 0, dp4)
                    }
                    val tvName = TextView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = item.name
                        textSize = 12f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                    val tvState = TextView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = if (item.ok) {
                            val okLabel = item.detail.ifBlank { "OK" }
                            "✓ $okLabel (${item.latencyMs} мс)"
                        } else {
                            "✕ ${item.detail}"
                        }
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (item.ok) R.color.status_ok else R.color.status_err
                            )
                        )
                    }
                    row.addView(tvName)
                    row.addView(tvState)
                    layoutMirrorList.addView(row)
                }
            }
        }

        isSettingsDialogOpen = true
        dialog.setOnDismissListener {
            isSettingsDialogOpen = false
            mirrorCheckJob?.cancel()
            showPendingUpdateIfReady()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            storage.useBuiltInPlayer = selectedBuiltIn
            storage.bufferProfile = selectedBufferProfile
            storage.usePublicJacRed = publicJacRedEnabled
            storage.useRutorFallback = rutorFallbackEnabled
            storage.autoCheckUpdates = autoCheckUpdatesEnabled
            storage.torrServeUrl = etTorrServe.text.toString()
            storage.customParserUrl = etCustomParser.text.toString()
            storage.parserApiKey = etApiKey.text.toString()
            val catalogChanged = storage.showTmdbShowcase != tmdbShowcaseEnabled ||
                storage.catalogProvider != selectedCatalogProvider ||
                storage.kinopoiskApiKey != etKinopoiskApiKey.text.toString().trim() ||
                storage.tmdbApiKey != etTmdbApiKey.text.toString().trim() ||
                storage.tmdbApiBaseUrl != etTmdbApiUrl.text.toString().trim().trimEnd('/').ifBlank { StorageManager.DEFAULT_TMDB_API_URL } ||
                storage.tmdbImageBaseUrl != etTmdbImageUrl.text.toString().trim().trimEnd('/').ifBlank { StorageManager.DEFAULT_TMDB_IMAGE_URL }

            storage.rutorMirrorUrl = etRutorMirror.text.toString()
            storage.showTmdbShowcase = tmdbShowcaseEnabled
            storage.catalogProvider = selectedCatalogProvider
            storage.kinopoiskApiKey = etKinopoiskApiKey.text.toString()
            storage.tmdbApiKey = etTmdbApiKey.text.toString()
            storage.tmdbApiBaseUrl = etTmdbApiUrl.text.toString()
            storage.tmdbImageBaseUrl = etTmdbImageUrl.text.toString()
            dialog.dismiss()
            if (catalogChanged) {
                tmdbShelves = null
                torrentShelves = null
                tmdbLoadedOnce = false
                loadTmdbShowcase(force = true)
            }
            refreshTorrServeStatus(autoStart = false)
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        if (focusCatalogKey) {
            when (selectedCatalogProvider) {
                StorageManager.CATALOG_PROVIDER_TORRENTS -> btnCatalogTorrents.requestFocus()
                StorageManager.CATALOG_PROVIDER_KINOPOISK -> etKinopoiskApiKey.requestFocus()
                else -> etTmdbApiKey.requestFocus()
            }
        } else if (selectedBuiltIn) {
            btnPlayerBuiltin.requestFocus()
        } else {
            btnPlayerExternal.requestFocus()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    companion object {
        private const val AUTO_UPDATE_COOLDOWN_MS = 15L * 60L * 1000L
        private val TRAILING_YEAR_REGEX = Regex("\\s+\\(?((?:19|20)\\d{2})\\)?$")
    }
}
