package com.torplay.tv

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MovieDetailsDialogHelper(
    private val activity: Activity,
    private val storage: StorageManager,
    private val uiScope: CoroutineScope,
    private val tmdbClient: TmdbClient,
    private val kinopoiskClient: KinopoiskClient,
    private val kinopoiskDevClient: KinopoiskDevClient,
    private val posterLoader: PosterLoader,
    private val enrichFromCache: (CatalogItem) -> CatalogItem,
    private val onRuntimeUpdated: (String, Int) -> Unit,
    private val onUpdateWatchlistButtonUi: () -> Unit,
    private val onFindTorrents: (CatalogItem) -> Unit
) {

    fun showMovieDetailsDialog(item: CatalogItem) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_movie_details)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.82).toInt(),
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
        val btnTrailer = dialog.findViewById<TextView>(R.id.btnDetailsTrailer)
        val btnFav = dialog.findViewById<TextView>(R.id.btnDetailsFavorite)
        val btnClose = dialog.findViewById<TextView>(R.id.btnDetailsClose)
        val layoutSimilar = dialog.findViewById<LinearLayout>(R.id.layoutDetailsSimilar)
        val progressSimilar = dialog.findViewById<ProgressBar>(R.id.progressDetailsSimilar)
        val rvSimilar = dialog.findViewById<RecyclerView>(R.id.rvDetailsSimilar)

        val effectiveItem = enrichFromCache(item)
        tvTitle.text = effectiveItem.titleWithYear()
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

        tvType.text = activity.getString(
            if (item.isSeries()) R.string.catalog_type_series else R.string.catalog_type_movie
        )

        var currentItem = effectiveItem
        var resolvedTrailerUrl: String? = null

        fun renderDetailsMeta(target: CatalogItem) {
            val parts = mutableListOf<String>()
            if (target.year.isNotBlank()) parts.add(target.year)
            val durText = target.formattedDuration()
            if (durText.isNotEmpty()) parts.add(durText)
            if (target.genreNames.isNotEmpty()) parts.add(target.genreNames.joinToString(", "))
            val statsText = target.formattedTorrentStats()
            if (statsText.isNotEmpty()) parts.add(statsText)
            tvMeta.text = parts.joinToString(" • ")
        }
        renderDetailsMeta(currentItem)

        var detailsJob: Job? = null
        var similarJob: Job? = null
        var trailerJob: Job? = null

        val needsKpDetails = currentItem.source == KinopoiskClient.SOURCE_ID &&
            (currentItem.overview.isBlank() || currentItem.runtimeMinutes <= 0) &&
            currentItem.id > 0
        val needsKpDevDetails = currentItem.source == KinopoiskDevClient.SOURCE_ID &&
            (currentItem.overview.isBlank() || currentItem.runtimeMinutes <= 0) &&
            currentItem.id > 0
        val needsTmdbRuntime = currentItem.source == "tmdb" &&
            currentItem.runtimeMinutes <= 0 &&
            currentItem.id > 0

        tvOverview.text = if ((needsKpDetails || needsKpDevDetails) && currentItem.overview.isBlank()) {
            activity.getString(R.string.catalog_loading_overview)
        } else {
            currentItem.overview.ifBlank { activity.getString(R.string.catalog_no_overview) }
        }

        val posterUrl = tmdbClient.buildPosterUrl(currentItem.posterPath)
        posterLoader.load(uiScope, ivPoster, posterUrl)

        if (needsKpDetails || needsKpDevDetails) {
            detailsJob = uiScope.launch {
                val full = if (needsKpDevDetails) {
                    kinopoiskDevClient.fetchFilmDetails(currentItem.id)
                } else {
                    kinopoiskClient.fetchFilmDetails(currentItem.id)
                }
                if (!dialog.isShowing) return@launch
                if (full != null) {
                    val mergedGenres = if (full.genreNames.isNotEmpty()) full.genreNames else currentItem.genreNames
                    val mergedYear = currentItem.year.ifBlank { full.year }
                    val mergedRuntime = if (full.runtimeMinutes > 0) full.runtimeMinutes else currentItem.runtimeMinutes
                    val mergedOverview = full.overview.ifBlank { currentItem.overview }
                    currentItem = currentItem.copy(
                        year = mergedYear,
                        genreNames = mergedGenres,
                        runtimeMinutes = mergedRuntime,
                        overview = mergedOverview,
                        rating = if (currentItem.rating > 0.05) currentItem.rating else full.rating
                    )
                    tvOverview.text = currentItem.overview.ifBlank { activity.getString(R.string.catalog_no_overview) }
                    if (ratingStr.isEmpty()) {
                        val newRating = currentItem.formattedRating()
                        if (newRating.isNotEmpty()) {
                            tvRating.text = newRating
                            tvRating.visibility = View.VISIBLE
                        }
                    }
                    renderDetailsMeta(currentItem)
                    if (mergedRuntime > 0) {
                        onRuntimeUpdated(currentItem.stableKey(), mergedRuntime)
                    }
                } else {
                    tvOverview.text = currentItem.overview.ifBlank { activity.getString(R.string.catalog_no_overview) }
                }
            }
        } else if (needsTmdbRuntime) {
            detailsJob = uiScope.launch {
                val mins = runCatching {
                    tmdbClient.fetchRuntimeMinutes(currentItem.id, currentItem.isSeries())
                }.getOrDefault(0)
                if (!dialog.isShowing || mins <= 0) return@launch
                currentItem = currentItem.copy(runtimeMinutes = mins)
                renderDetailsMeta(currentItem)
                onRuntimeUpdated(currentItem.stableKey(), mins)
            }
        }

        // Similar Movies / Series Ribbon
        val similarAdapter = CatalogAdapter(
            scope = uiScope,
            tmdbClient = tmdbClient,
            posterLoader = posterLoader,
            compact = true,
            onItemClick = { similarItem ->
                dialog.dismiss()
                showMovieDetailsDialog(similarItem)
            }
        )
        rvSimilar.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        rvSimilar.adapter = similarAdapter

        fun updateActionButtonsDownFocus(similarVisible: Boolean) {
            val downTarget = if (similarVisible) R.id.rvDetailsSimilar else View.NO_ID
            btnFind.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.btnDetailsFindTorrents
            btnTrailer.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.btnDetailsTrailer
            btnFav.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.btnDetailsFavorite
            btnClose.nextFocusDownId = if (downTarget != View.NO_ID) downTarget else R.id.btnDetailsClose
        }
        updateActionButtonsDownFocus(false)

        if (currentItem.id > 0) {
            similarJob = uiScope.launch {
                val similar = fetchSimilarForItem(currentItem)
                if (!dialog.isShowing) return@launch
                progressSimilar.visibility = View.GONE
                if (similar.isNotEmpty()) {
                    similarAdapter.submitList(similar)
                    layoutSimilar.visibility = View.VISIBLE
                    updateActionButtonsDownFocus(true)
                } else {
                    layoutSimilar.visibility = View.GONE
                    updateActionButtonsDownFocus(false)
                }
            }
        }

        // Prefetch trailer URL in background so tapping "Трейлер" opens instantly
        if (currentItem.id > 0) {
            trailerJob = uiScope.launch {
                resolvedTrailerUrl = fetchTrailerForItem(currentItem)
            }
        }

        // Watchlist ("Буду смотреть") button right inside the Movie Details dialog
        fun renderWatchlistBtn() {
            val saved = storage.isInWatchlist(currentItem)
            btnFav.isSelected = saved
            btnFav.text = activity.getString(
                if (saved) R.string.details_btn_watchlist_saved else R.string.details_btn_watchlist_add
            )
        }
        btnFav.visibility = View.VISIBLE
        btnFind.nextFocusRightId = R.id.btnDetailsTrailer
        btnTrailer.nextFocusLeftId = R.id.btnDetailsFindTorrents
        btnTrailer.nextFocusRightId = R.id.btnDetailsFavorite
        btnFav.nextFocusLeftId = R.id.btnDetailsTrailer
        btnFav.nextFocusRightId = R.id.btnDetailsClose
        btnClose.nextFocusLeftId = R.id.btnDetailsFavorite
        renderWatchlistBtn()

        btnFav.setOnClickListener {
            val added = storage.toggleWatchlist(currentItem)
            renderWatchlistBtn()
            onUpdateWatchlistButtonUi()
            Toast.makeText(
                activity,
                if (added) "Добавлено в «Буду смотреть»" else "Убрано из «Буду смотреть»",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnTrailer.setOnClickListener {
            val cachedUrl = resolvedTrailerUrl
            if (!cachedUrl.isNullOrBlank()) {
                TrailerLauncher.openTrailer(activity, cachedUrl, currentItem.title, currentItem.year)
                return@setOnClickListener
            }
            btnTrailer.isEnabled = false
            Toast.makeText(activity, "Ищем трейлер…", Toast.LENGTH_SHORT).show()
            uiScope.launch {
                val url = fetchTrailerForItem(currentItem)
                resolvedTrailerUrl = url
                btnTrailer.isEnabled = true
                if (!activity.isFinishing && !activity.isDestroyed) {
                    TrailerLauncher.openTrailer(activity, url, currentItem.title, currentItem.year)
                }
            }
        }

        btnFind.setOnClickListener {
            dialog.dismiss()
            onFindTorrents(item)
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            detailsJob?.cancel()
            similarJob?.cancel()
            trailerJob?.cancel()
        }

        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        btnFind.requestFocus()
    }

    private suspend fun fetchSimilarForItem(item: CatalogItem): List<CatalogItem> = withContext(Dispatchers.IO) {
        val primary = when (item.source) {
            KinopoiskDevClient.SOURCE_ID -> kinopoiskDevClient.fetchSimilarMovies(item.id)
            KinopoiskClient.SOURCE_ID -> kinopoiskClient.fetchSimilarMovies(item.id)
            else -> tmdbClient.fetchSimilarMovies(item.id, item.isSeries())
        }
        if (primary.isNotEmpty()) {
            return@withContext primary.filterNot { it.id == item.id && it.source == item.source }.take(12)
        }
        // Fallback: if Kinopoisk didn't return similars, resolve via TMDB search + recommendations
        if (item.source != "tmdb" && storage.tmdbApiKey.isNotBlank()) {
            val query = item.originalTitle.ifBlank { item.title }
            val matched = tmdbClient.searchMulti(query).firstOrNull { it.isSeries() == item.isSeries() }
                ?: tmdbClient.searchMulti(item.title).firstOrNull()
            if (matched != null && matched.id > 0) {
                return@withContext tmdbClient.fetchSimilarMovies(matched.id, matched.isSeries()).take(12)
            }
        }
        emptyList()
    }

    private suspend fun fetchTrailerForItem(item: CatalogItem): String? = withContext(Dispatchers.IO) {
        val primary = when (item.source) {
            KinopoiskDevClient.SOURCE_ID -> kinopoiskDevClient.fetchTrailerUrl(item.id)
            KinopoiskClient.SOURCE_ID -> kinopoiskClient.fetchTrailerUrl(item.id)
            else -> tmdbClient.fetchTrailerUrl(item.id, item.isSeries())
        }
        if (!primary.isNullOrBlank()) return@withContext primary

        // Cross-fallback to TMDB videos if Kinopoisk had no YouTube trailer
        if (item.source != "tmdb" && storage.tmdbApiKey.isNotBlank()) {
            val query = item.originalTitle.ifBlank { item.title }
            val matched = tmdbClient.searchMulti(query).firstOrNull { it.isSeries() == item.isSeries() }
                ?: tmdbClient.searchMulti(item.title).firstOrNull()
            if (matched != null && matched.id > 0) {
                val tmdbUrl = tmdbClient.fetchTrailerUrl(matched.id, matched.isSeries())
                if (!tmdbUrl.isNullOrBlank()) return@withContext tmdbUrl
            }
        }
        null
    }
}
