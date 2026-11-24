package com.torplay.tv

import android.app.Dialog
import android.content.Context
import android.os.Bundle
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class QualityFilter { ALL, UHD_4K, FHD_1080, HD_720, HDR }
    private enum class SortMode { SEEDS, SIZE }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var storage: StorageManager
    private lateinit var searchEngine: SearchEngine
    private lateinit var torrServeClient: TorrServeClient
    private lateinit var playerController: PlayerController
    private lateinit var adapter: TorrentAdapter

    private lateinit var btnTorrServeStatus: LinearLayout
    private lateinit var viewStatusDot: View
    private lateinit var tvTorrServeStatus: TextView
    private lateinit var btnSettings: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: TextView
    private lateinit var layoutFilters: LinearLayout
    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilter4k: TextView
    private lateinit var btnFilter1080: TextView
    private lateinit var btnFilter720: TextView
    private lateinit var btnFilterHdr: TextView
    private lateinit var btnSort: TextView
    private lateinit var tvSearchMeta: TextView
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
    private var lastPlayedHash: String = ""
    private var waitingForPlayerReturn: Boolean = false

    private var allResults: List<TorrentItem> = emptyList()
    private var lastSourceName: String = ""
    private var currentFilter: QualityFilter = QualityFilter.ALL
    private var currentSort: SortMode = SortMode.SEEDS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storage = StorageManager(this)
        searchEngine = SearchEngine(storage)
        torrServeClient = TorrServeClient(storage)
        playerController = PlayerController(this)

        bindViews()
        setupListeners()
        updateFilterChipsUi()

        if (storage.lastQuery.isNotBlank()) {
            etSearch.setText(storage.lastQuery)
            etSearch.setSelection(storage.lastQuery.length)
        }
        etSearch.requestFocus()
        refreshTorrServeStatus(autoStart = false)
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
        refreshTorrServeStatus(autoStart = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
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
        super.onBackPressed()
    }

    private fun bindViews() {
        btnTorrServeStatus = findViewById(R.id.btnTorrServeStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvTorrServeStatus = findViewById(R.id.tvTorrServeStatus)
        btnSettings = findViewById(R.id.btnSettings)
        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        layoutFilters = findViewById(R.id.layoutFilters)
        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilter4k = findViewById(R.id.btnFilter4k)
        btnFilter1080 = findViewById(R.id.btnFilter1080)
        btnFilter720 = findViewById(R.id.btnFilter720)
        btnFilterHdr = findViewById(R.id.btnFilterHdr)
        btnSort = findViewById(R.id.btnSort)
        tvSearchMeta = findViewById(R.id.tvSearchMeta)
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
    }

    private fun setupListeners() {
        btnTorrServeStatus.setOnClickListener {
            refreshTorrServeStatus(autoStart = true)
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
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

        btnSort.setOnClickListener {
            currentSort = if (currentSort == SortMode.SEEDS) SortMode.SIZE else SortMode.SEEDS
            btnSort.text = if (currentSort == SortMode.SEEDS) {
                getString(R.string.sort_seeds)
            } else {
                getString(R.string.sort_size)
            }
            renderFilteredResults(focusFirst = false)
        }
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
    }

    private fun renderFilteredResults(focusFirst: Boolean) {
        val filtered = allResults.filter { item ->
            when (currentFilter) {
                QualityFilter.ALL -> true
                QualityFilter.UHD_4K -> item.quality == "4K"
                QualityFilter.FHD_1080 -> item.quality == "1080p"
                QualityFilter.HD_720 -> item.quality == "720p"
                QualityFilter.HDR -> item.isHdr
            }
        }

        val sorted = when (currentSort) {
            SortMode.SEEDS -> filtered.sortedWith(
                compareByDescending<TorrentItem> { it.seeds }.thenByDescending { it.sizeBytes }
            )
            SortMode.SIZE -> filtered.sortedWith(
                compareByDescending<TorrentItem> { it.sizeBytes }.thenByDescending { it.seeds }
            )
        }

        adapter.submitList(sorted)
        tvSearchMeta.text = "Найдено: ${sorted.size}/${allResults.size} • $lastSourceName"

        if (sorted.isNotEmpty()) {
            layoutEmpty.visibility = View.GONE
            if (focusFirst) {
                rvResults.post {
                    rvResults.scrollToPosition(0)
                    rvResults.getChildAt(0)?.requestFocus()
                }
            }
        } else {
            layoutEmpty.visibility = View.VISIBLE
            tvEmptyMessage.text = getString(R.string.no_filtered_results)
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

        searchJob?.cancel()
        searchJob = uiScope.launch {
            allResults = emptyList()
            currentFilter = QualityFilter.ALL
            updateFilterChipsUi()
            adapter.submitList(emptyList())
            layoutFilters.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            progressSearch.visibility = View.VISIBLE
            tvEmptyMessage.text = getString(R.string.searching)

            val response = searchEngine.search(query)
            progressSearch.visibility = View.GONE

            if (response.items.isNotEmpty()) {
                allResults = response.items
                lastSourceName = response.sourceName
                layoutFilters.visibility = View.VISIBLE
                renderFilteredResults(focusFirst = true)
            } else {
                layoutFilters.visibility = View.GONE
                layoutEmpty.visibility = View.VISIBLE
                tvEmptyMessage.text = response.error ?: getString(R.string.no_results)
            }
        }
    }

    private fun openTorrent(item: TorrentItem) {
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

            if (files.size > 1) {
                hideLoadingOverlay()
                showEpisodePickerDialog(item.title, hash, files)
            } else {
                val file = files.firstOrNull()
                    ?: TorrServeFile(id = 1, path = "${item.title}.mkv", length = 0L)
                bufferAndLaunchFile(hash, item.title, file)
            }
        }
    }

    private fun showEpisodePickerDialog(torrentTitle: String, hash: String, files: List<TorrServeFile>) {
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
                    bufferAndLaunchFile(hash, "$torrentTitle — ${selectedFile.fileName}", selectedFile)
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

        // Focus first unwatched episode (or 0)
        val firstUnwatched = files.indexOfFirst { !storage.isEpisodeWatched(hash, it.id) }
            .takeIf { it >= 0 } ?: 0
        rvEpisodes.post {
            rvEpisodes.scrollToPosition(firstUnwatched)
            rvEpisodes.findViewHolderForAdapterPosition(firstUnwatched)?.itemView?.requestFocus()
                ?: rvEpisodes.getChildAt(0)?.requestFocus()
        }
    }

    private suspend fun bufferAndLaunchFile(hash: String, displayTitle: String, file: TorrServeFile) {
        showLoadingOverlay(displayTitle, "Буферизация потока…", showBuffer = true)
        progressBuffer.progress = 0
        tvLoadingStats.text = "Поиск пиров…"

        torrServeClient.preloadWithProgress(hash, file.id, maxWaitMs = 12_000L) { info ->
            val pct = info.preloadPercent()
            progressBuffer.progress = pct
            tvLoadingStep.text = if (info.statString.isNotBlank()) info.statString else "Буферизация…"
            tvLoadingStats.text = "Буфер: $pct%  •  ${info.formattedSpeed()}  •  Пиры: ${info.activePeers}/${info.totalPeers} (сиды: ${info.connectedSeeders})"
        }

        val streamUrl = torrServeClient.buildStreamUrl(hash, file.id, file.fileName)
        tvLoadingStep.text = "Запуск MX Player…"

        val launchResult = playerController.playExternal(streamUrl, displayTitle)
        hideLoadingOverlay()

        if (launchResult.launched) {
            storage.markEpisodeWatched(hash, file.id)
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
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.62).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val etTorrServe = dialog.findViewById<EditText>(R.id.etTorrServeUrl)
        val etCustomParser = dialog.findViewById<EditText>(R.id.etCustomParserUrl)
        val etApiKey = dialog.findViewById<EditText>(R.id.etApiKey)
        val etRutorMirror = dialog.findViewById<EditText>(R.id.etRutorMirror)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnCancelSettings)
        val btnSave = dialog.findViewById<TextView>(R.id.btnSaveSettings)

        etTorrServe.setText(storage.torrServeUrl)
        etCustomParser.setText(storage.customParserUrl)
        etApiKey.setText(storage.parserApiKey)
        etRutorMirror.setText(storage.rutorMirrorUrl)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            storage.torrServeUrl = etTorrServe.text.toString()
            storage.customParserUrl = etCustomParser.text.toString()
            storage.parserApiKey = etApiKey.text.toString()
            storage.rutorMirrorUrl = etRutorMirror.text.toString()
            dialog.dismiss()
            refreshTorrServeStatus(autoStart = false)
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        etTorrServe.requestFocus()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }
}
