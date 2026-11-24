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
    private lateinit var tvSearchMeta: TextView
    private lateinit var rvResults: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var progressSearch: ProgressBar
    private lateinit var tvEmptyMessage: TextView
    private lateinit var layoutLoadingOverlay: FrameLayout
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingStep: TextView

    private var searchJob: Job? = null
    private var launchJob: Job? = null
    private var lastPlayedHash: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storage = StorageManager(this)
        searchEngine = SearchEngine(storage)
        torrServeClient = TorrServeClient(storage)
        playerController = PlayerController(this)

        bindViews()
        setupListeners()

        if (storage.lastQuery.isNotBlank()) {
            etSearch.setText(storage.lastQuery)
            etSearch.setSelection(storage.lastQuery.length)
        }
        etSearch.requestFocus()
        refreshTorrServeStatus(autoStart = false)
    }

    override fun onResume() {
        super.onResume()
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
        tvSearchMeta = findViewById(R.id.tvSearchMeta)
        rvResults = findViewById(R.id.rvResults)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        progressSearch = findViewById(R.id.progressSearch)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        layoutLoadingOverlay = findViewById(R.id.layoutLoadingOverlay)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingStep = findViewById(R.id.tvLoadingStep)

        adapter = TorrentAdapter { item -> openTorrentInPlayer(item) }
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
            adapter.submitList(emptyList())
            tvSearchMeta.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            progressSearch.visibility = View.VISIBLE
            tvEmptyMessage.text = getString(R.string.searching)

            val response = searchEngine.search(query)
            progressSearch.visibility = View.GONE

            if (response.items.isNotEmpty()) {
                layoutEmpty.visibility = View.GONE
                adapter.submitList(response.items)
                tvSearchMeta.text = "Найдено: ${response.items.size} • Источник: ${response.sourceName}"
                tvSearchMeta.visibility = View.VISIBLE
                rvResults.post {
                    rvResults.scrollToPosition(0)
                    rvResults.getChildAt(0)?.requestFocus()
                }
            } else {
                layoutEmpty.visibility = View.VISIBLE
                tvEmptyMessage.text = response.error ?: getString(R.string.no_results)
            }
        }
    }

    private fun openTorrentInPlayer(item: TorrentItem) {
        val link = item.playableLink
        if (link.isBlank()) {
            Toast.makeText(this, "У раздачи нет magnet-ссылки", Toast.LENGTH_SHORT).show()
            return
        }

        launchJob?.cancel()
        launchJob = uiScope.launch {
            showLoadingOverlay(item.title, "Проверка TorrServe…")

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

            // Drop previous torrent to keep Mi Box RAM clean
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
                added.files
            } else {
                torrServeClient.waitForVideoFiles(hash, timeoutMs = 12_000L)
            }

            val primaryFile = torrServeClient.pickPrimaryVideoFile(files)
            val fileId = primaryFile?.id ?: 1
            val fileName = primaryFile?.fileName ?: "${item.title}.mkv"

            val streamUrl = torrServeClient.buildStreamUrl(hash, fileId, fileName)

            tvLoadingStep.text = "Запуск MX Player…"
            val launchResult = playerController.playExternal(streamUrl, item.title)
            hideLoadingOverlay()

            if (!launchResult.launched) {
                Toast.makeText(
                    this@MainActivity,
                    launchResult.error ?: "Не удалось открыть плеер",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showLoadingOverlay(title: String, step: String) {
        tvLoadingTitle.text = title
        tvLoadingStep.text = step
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
