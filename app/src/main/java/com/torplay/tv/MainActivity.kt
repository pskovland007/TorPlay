package com.torplay.tv

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var playerController: PlayerController
    private lateinit var updateManager: UpdateManager
    private lateinit var adapter: TorrentAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var favoriteAdapter: FavoriteAdapter
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    private lateinit var btnTorrServeStatus: LinearLayout
    private lateinit var viewStatusDot: View
    private lateinit var tvTorrServeStatus: TextView
    private lateinit var btnFavoriteQuery: TextView
    private lateinit var btnSettings: TextView
    private lateinit var etSearch: EditText
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
    private lateinit var layoutHomeShelves: LinearLayout
    private lateinit var tvFavoritesHeader: TextView
    private lateinit var rvFavorites: RecyclerView
    private lateinit var tvHistoryHeader: TextView
    private lateinit var rvHistory: RecyclerView
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
    private var lastPlayedHash: String = ""
    private var waitingForPlayerReturn: Boolean = false
    private var hasCheckedUpdatesOnStart: Boolean = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storage = StorageManager(this)
        searchEngine = SearchEngine(storage)
        torrServeClient = TorrServeClient(storage)
        playerController = PlayerController(this)
        updateManager = UpdateManager(this)

        hideCamReleases = storage.hideCamReleases
        onlyLiveSeeds = storage.onlyLiveSeeds

        bindViews()
        setupListeners()
        updateFilterChipsUi()
        updateFavoriteButtonUi()

        if (storage.lastQuery.isNotBlank()) {
            etSearch.setText(storage.lastQuery)
            etSearch.setSelection(storage.lastQuery.length)
        }
        refreshHomeShelves()
        etSearch.requestFocus()
        refreshTorrServeStatus(autoStart = false)
        checkUpdatesOnStartup()
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
        if (layoutFilters.visibility == View.VISIBLE || rvResults.visibility == View.VISIBLE) {
            searchJob?.cancel()
            allResults = emptyList()
            adapter.submitList(emptyList())
            layoutFilters.visibility = View.GONE
            rvResults.visibility = View.GONE
            refreshHomeShelves()
            etSearch.requestFocus()
            return
        }
        super.onBackPressed()
    }

    private fun checkUpdatesOnStartup() {
        if (hasCheckedUpdatesOnStart || !storage.autoCheckUpdates) return
        hasCheckedUpdatesOnStart = true
        uiScope.launch {
            delay(1_800L)
            val release = updateManager.checkLatestRelease() ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            if (layoutLoadingOverlay.visibility == View.VISIBLE) return@launch
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
        btnFavoriteQuery = findViewById(R.id.btnFavoriteQuery)
        btnSettings = findViewById(R.id.btnSettings)
        etSearch = findViewById(R.id.etSearch)
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
        layoutHomeShelves = findViewById(R.id.layoutHomeShelves)
        tvFavoritesHeader = findViewById(R.id.tvFavoritesHeader)
        rvFavorites = findViewById(R.id.rvFavorites)
        tvHistoryHeader = findViewById(R.id.tvHistoryHeader)
        rvHistory = findViewById(R.id.rvHistory)
        rvResults = findViewById(R.id.rvResults)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressSearch = findViewById(R.id.progressSearch)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        layoutLoadingOverlay = findViewById(R.id.layoutLoadingOverlay)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingStep = findViewById(R.id.tvLoadingStep)
        progressBuffer = findViewById(R.id.progressBuffer)
        tvLoadingStats = findViewById(R.id.tvLoadingStats)

        adapter = TorrentAdapter { item -> openTorrent(item) }
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

        favoriteAdapter = FavoriteAdapter(
            onItemClick = { fav ->
                etSearch.setText(fav.query)
                etSearch.setSelection(fav.query.length)
                updateFavoriteButtonUi()
                performSearch()
            },
            onItemMenu = { fav ->
                showShelfActionDialog(
                    title = getString(R.string.action_favorite_title),
                    subtitle = fav.query,
                    deleteOneLabel = getString(R.string.action_favorite_delete_one),
                    clearAllLabel = getString(R.string.action_favorite_clear_all),
                    onDeleteOne = {
                        storage.removeFavorite(fav.query)
                        updateFavoriteButtonUi()
                        refreshHomeShelves()
                        Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                    },
                    onClearAll = {
                        storage.clearFavorites()
                        updateFavoriteButtonUi()
                        refreshHomeShelves()
                        Toast.makeText(this, "Избранное очищено", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        )
        rvFavorites.layoutManager = LinearLayoutManager(this)
        rvFavorites.adapter = favoriteAdapter

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
    }

    private fun setupListeners() {
        btnTorrServeStatus.setOnClickListener {
            refreshTorrServeStatus(autoStart = true)
        }

        btnFavoriteQuery.setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isBlank()) {
                Toast.makeText(this, "Введите название, чтобы добавить в избранное", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val added = storage.toggleFavorite(q)
            updateFavoriteButtonUi()
            if (layoutFilters.visibility != View.VISIBLE && rvResults.visibility != View.VISIBLE) {
                refreshHomeShelves()
            }
            Toast.makeText(
                this,
                if (added) "Добавлено в избранное" else "Удалено из избранного",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
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
        val recentVisible = rvRecentSearches.visibility == View.VISIBLE
        val favsVisible = layoutHomeShelves.visibility == View.VISIBLE && rvFavorites.visibility == View.VISIBLE
        val historyVisible = layoutHomeShelves.visibility == View.VISIBLE && rvHistory.visibility == View.VISIBLE

        if (filtersVisible) {
            etSearch.nextFocusDownId = R.id.btnFilterAll
            btnVoiceSearch.nextFocusDownId = R.id.btnFilterLiveSeeds
            btnSearch.nextFocusDownId = R.id.btnSort

            val filterDown = if (resultsVisible) R.id.rvResults else View.NO_ID
            btnFilterAll.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterAll
            btnFilter4k.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilter4k
            btnFilter1080.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilter1080
            btnFilter720.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilter720
            btnFilterHdr.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterHdr
            btnFilterNoCam.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterNoCam
            btnFilterLiveSeeds.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnFilterLiveSeeds
            btnSort.nextFocusDownId = if (filterDown != View.NO_ID) filterDown else R.id.btnSort
        } else {
            val downTarget = when {
                recentVisible -> R.id.rvRecentSearches
                favsVisible -> R.id.rvFavorites
                historyVisible -> R.id.rvHistory
                else -> View.NO_ID
            }
            etSearch.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.etSearch
            btnVoiceSearch.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.btnVoiceSearch
            btnSearch.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.btnSearch

            if (recentVisible) {
                val belowRecent = when {
                    favsVisible -> R.id.rvFavorites
                    historyVisible -> R.id.rvHistory
                    else -> View.NO_ID
                }
                rvRecentSearches.nextFocusDownId = belowRecent
            }

            rvFavorites.nextFocusUpId = if (recentVisible) R.id.rvRecentSearches else R.id.etSearch
            rvHistory.nextFocusUpId = when {
                favsVisible -> R.id.rvFavorites
                recentVisible -> R.id.rvRecentSearches
                else -> R.id.etSearch
            }
        }
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
        val fav = storage.isFavorite(etSearch.text.toString())
        btnFavoriteQuery.text = getString(if (fav) R.string.btn_fav_active else R.string.btn_fav_toggle)
    }

    private fun refreshHomeShelves() {
        val recents = storage.getRecentSearches()
        val favs = storage.getFavorites()
        val history = storage.getWatchHistory()

        if (recents.isNotEmpty()) {
            rvRecentSearches.visibility = View.VISIBLE
            recentSearchAdapter.submitList(recents)
        } else {
            rvRecentSearches.visibility = View.GONE
        }

        if (favs.isNotEmpty()) {
            tvFavoritesHeader.visibility = View.VISIBLE
            rvFavorites.visibility = View.VISIBLE
            favoriteAdapter.submitList(favs)
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

        val hasShelves = favs.isNotEmpty() || history.isNotEmpty()
        layoutHomeShelves.visibility = if (hasShelves) View.VISIBLE else View.GONE
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

    private fun refreshTorrServeStatus(autoStart: Boolean) {
        uiScope.launch {
            if (autoStart) {
                setStatusIndicator(R.color.status_warn, getString(R.string.status_starting))
            } else {
                setStatusIndicator(R.color.status_warn, getString(R.string.status_checking))
            }

            val version = if (autoStart) {
                torrServeClient.ensureRunning(this@MainActivity)
            } else {
                torrServeClient.checkEcho()
            }

            if (version != null) {
                val shortVer = version.take(18)
                setStatusIndicator(R.color.status_ok, "TorrServe: $shortVer")
            } else {
                setStatusIndicator(R.color.status_err, getString(R.string.status_offline))
                if (autoStart) {
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

    private fun performSearch() {
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
        searchJob = uiScope.launch {
            allResults = emptyList()
            currentFilter = QualityFilter.ALL
            updateFilterChipsUi()
            adapter.submitList(emptyList())
            rvRecentSearches.visibility = View.GONE
            layoutFilters.visibility = View.GONE
            layoutHomeShelves.visibility = View.GONE
            rvResults.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            progressSearch.visibility = View.VISIBLE
            tvEmptyMessage.text = getString(R.string.searching)
            updateDpadFocusRoutes()

            val response = searchEngine.search(query)
            progressSearch.visibility = View.GONE

            if (response.items.isNotEmpty()) {
                allResults = response.items
                lastSourceName = response.sourceName
                layoutHomeShelves.visibility = View.GONE
                layoutFilters.visibility = View.VISIBLE
                renderFilteredResults(focusFirst = true)
            } else {
                layoutFilters.visibility = View.GONE
                layoutHomeShelves.visibility = View.GONE
                rvResults.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
                tvEmptyMessage.text = response.error ?: getString(R.string.no_results)
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

            val version = torrServeClient.ensureRunning(this@MainActivity)
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
        tvCount.text = "Видеофайлов: ${files.size} • Нажмите OK для просмотра"

        val episodeAdapter = EpisodeAdapter(
            files = files,
            watchedChecker = { fileId -> storage.isEpisodeWatched(hash, fileId) },
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
            }
        )

        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.adapter = episodeAdapter

        dialog.setOnCancelListener {
            if (lastPlayedHash.isNotBlank()) {
                val h = lastPlayedHash
                lastPlayedHash = ""
                uiScope.launch { torrServeClient.stopAndRemoveTorrent(h) }
            }
        }

        dialog.show()

        val firstUnwatched = files.indexOfFirst { !storage.isEpisodeWatched(hash, it.id) }
            .takeIf { it >= 0 } ?: 0
        rvEpisodes.post {
            rvEpisodes.scrollToPosition(firstUnwatched)
            rvEpisodes.findViewHolderForAdapterPosition(firstUnwatched)?.itemView?.requestFocus()
                ?: rvEpisodes.getChildAt(0)?.requestFocus()
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

        torrServeClient.preloadWithProgress(hash, file.id, maxWaitMs = 12_000L) { info ->
            val pct = info.preloadPercent()
            progressBuffer.progress = pct
            tvLoadingStep.text = if (info.statString.isNotBlank()) info.statString else "Буферизация…"
            tvLoadingStats.text = "Буфер: $pct%  •  ${info.formattedSpeed()}  •  Пиры: ${info.activePeers}/${info.totalPeers} (сиды: ${info.connectedSeeders})"
        }

        val streamUrl = torrServeClient.buildStreamUrl(hash, file.id, file.fileName)
        val savedPos = storage.getSavedPositionMs(link, file.id)

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
            storage.markEpisodeWatched(hash, file.id)
            if (!storage.useBuiltInPlayer) {
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

    private fun showSettingsDialog() {
        hideKeyboard()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.72).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val tvVersion = dialog.findViewById<TextView>(R.id.tvSettingsVersion)
        val btnCheckUpdates = dialog.findViewById<TextView>(R.id.btnCheckUpdates)
        val btnPlayerBuiltin = dialog.findViewById<TextView>(R.id.btnPlayerBuiltin)
        val btnPlayerExternal = dialog.findViewById<TextView>(R.id.btnPlayerExternal)
        val btnTogglePublicJacRed = dialog.findViewById<TextView>(R.id.btnTogglePublicJacRed)
        val btnToggleRutorFallback = dialog.findViewById<TextView>(R.id.btnToggleRutorFallback)
        val btnToggleAutoUpdate = dialog.findViewById<TextView>(R.id.btnToggleAutoUpdate)
        val etTorrServe = dialog.findViewById<EditText>(R.id.etTorrServeUrl)
        val etCustomParser = dialog.findViewById<EditText>(R.id.etCustomParserUrl)
        val etApiKey = dialog.findViewById<EditText>(R.id.etApiKey)
        val etRutorMirror = dialog.findViewById<EditText>(R.id.etRutorMirror)
        val layoutMirrorResults = dialog.findViewById<LinearLayout>(R.id.layoutMirrorResults)
        val progressMirrorCheck = dialog.findViewById<ProgressBar>(R.id.progressMirrorCheck)
        val tvMirrorCheckStatus = dialog.findViewById<TextView>(R.id.tvMirrorCheckStatus)
        val layoutMirrorList = dialog.findViewById<LinearLayout>(R.id.layoutMirrorList)
        val btnCheckMirrors = dialog.findViewById<TextView>(R.id.btnCheckMirrors)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnCancelSettings)
        val btnSave = dialog.findViewById<TextView>(R.id.btnSaveSettings)

        tvVersion.text = "Версия ${BuildConfig.VERSION_NAME}"

        var selectedBuiltIn = storage.useBuiltInPlayer
        var publicJacRedEnabled = storage.usePublicJacRed
        var rutorFallbackEnabled = storage.useRutorFallback
        var autoCheckUpdatesEnabled = storage.autoCheckUpdates

        fun refreshPlayerChips() {
            btnPlayerBuiltin.isSelected = selectedBuiltIn
            btnPlayerExternal.isSelected = !selectedBuiltIn
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
        }

        refreshPlayerChips()
        refreshSourceChips()

        btnPlayerBuiltin.setOnClickListener {
            selectedBuiltIn = true
            refreshPlayerChips()
        }
        btnPlayerExternal.setOnClickListener {
            selectedBuiltIn = false
            refreshPlayerChips()
        }
        btnTogglePublicJacRed.setOnClickListener {
            publicJacRedEnabled = !publicJacRedEnabled
            refreshSourceChips()
        }
        btnToggleRutorFallback.setOnClickListener {
            rutorFallbackEnabled = !rutorFallbackEnabled
            refreshSourceChips()
        }
        btnToggleAutoUpdate.setOnClickListener {
            autoCheckUpdatesEnabled = !autoCheckUpdatesEnabled
            refreshSourceChips()
        }

        btnCheckUpdates.setOnClickListener {
            Toast.makeText(this, "Проверяем релизы на GitHub…", Toast.LENGTH_SHORT).show()
            uiScope.launch {
                val release = updateManager.checkLatestRelease()
                if (release != null) {
                    updateManager.showUpdateDialog(
                        activityContext = this@MainActivity,
                        release = release,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        manualCheck = true
                    )
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось получить список релизов с GitHub",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        btnCheckMirrors.setOnClickListener {
            mirrorCheckJob?.cancel()
            layoutMirrorResults.visibility = View.VISIBLE
            progressMirrorCheck.visibility = View.VISIBLE
            tvMirrorCheckStatus.text = getString(R.string.checking_mirrors)
            layoutMirrorList.removeAllViews()

            val customUrlInput = etCustomParser.text.toString()
            val apiKeyInput = etApiKey.text.toString()
            val rutorInput = etRutorMirror.text.toString()

            mirrorCheckJob = uiScope.launch {
                val results = searchEngine.checkMirrors(
                    customUrl = customUrlInput,
                    apiKey = apiKeyInput,
                    rutorUrl = rutorInput
                )
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
                            "✓ OK (${item.latencyMs} мс)"
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

        etTorrServe.setText(storage.torrServeUrl)
        etCustomParser.setText(storage.customParserUrl)
        etApiKey.setText(storage.parserApiKey)
        etRutorMirror.setText(storage.rutorMirrorUrl)

        dialog.setOnDismissListener {
            mirrorCheckJob?.cancel()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            storage.useBuiltInPlayer = selectedBuiltIn
            storage.usePublicJacRed = publicJacRedEnabled
            storage.useRutorFallback = rutorFallbackEnabled
            storage.autoCheckUpdates = autoCheckUpdatesEnabled
            storage.torrServeUrl = etTorrServe.text.toString()
            storage.customParserUrl = etCustomParser.text.toString()
            storage.parserApiKey = etApiKey.text.toString()
            storage.rutorMirrorUrl = etRutorMirror.text.toString()
            dialog.dismiss()
            refreshTorrServeStatus(autoStart = false)
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        if (selectedBuiltIn) {
            btnPlayerBuiltin.requestFocus()
        } else {
            btnPlayerExternal.requestFocus()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }
}
