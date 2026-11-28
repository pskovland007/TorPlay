package com.torplay.tv

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class SavedListsDialogHelper(
    private val activity: Activity,
    private val storage: StorageManager,
    private val uiScope: CoroutineScope,
    private val tmdbClient: TmdbClient,
    private val posterLoader: PosterLoader,
    private val applyCachedMeta: (List<CatalogItem>) -> List<CatalogItem>,
    private val onOpenTorrent: (TorrentItem) -> Unit,
    private val onOpenHistoryEntry: (TorrentItem, WatchHistoryEntry) -> Unit,
    private val onToggleFavoriteTorrent: (TorrentItem) -> Unit,
    private val onToggleFavoriteFromHistory: (WatchHistoryEntry) -> Boolean,
    private val onSearchCleanQuery: (String) -> Unit,
    private val onOpenMovieDetails: (CatalogItem) -> Unit,
    private val onRefreshFavoritesUi: () -> Unit,
    private val onUpdateWatchlistButtonUi: () -> Unit,
    private val onRefreshHomeShelves: () -> Unit
) {

    fun showShelfActionDialog(
        title: String,
        subtitle: String,
        deleteOneLabel: String,
        clearAllLabel: String,
        extraActionLabel: String? = null,
        onExtraAction: (() -> Unit)? = null,
        onDeleteOne: () -> Unit,
        onClearAll: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_shelf_actions)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.46).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvActionDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvActionDialogSubtitle)
        val btnExtra = dialog.findViewById<TextView>(R.id.btnActionExtra)
        val btnDeleteOne = dialog.findViewById<TextView>(R.id.btnActionDeleteOne)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnActionClearAll)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnActionCancel)

        tvTitle.text = title
        tvSubtitle.text = subtitle
        btnDeleteOne.text = deleteOneLabel
        btnClearAll.text = clearAllLabel

        val hasExtra = !extraActionLabel.isNullOrBlank() && onExtraAction != null
        if (hasExtra) {
            btnExtra.visibility = View.VISIBLE
            btnExtra.text = extraActionLabel
            btnDeleteOne.nextFocusUpId = R.id.btnActionExtra
            btnExtra.setOnClickListener {
                dialog.dismiss()
                onExtraAction?.invoke()
            }
        } else {
            btnExtra.visibility = View.GONE
            btnDeleteOne.nextFocusUpId = R.id.btnActionDeleteOne
        }

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
        SystemUiHelper.applyImmersiveToDialog(dialog)
        if (hasExtra) {
            btnExtra.requestFocus()
        } else {
            btnDeleteOne.requestFocus()
        }
    }

    fun showTorrentShowcaseActionDialog(item: TorrentItem) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_showcase_actions)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.50).toInt(),
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
        btnFav.text = activity.getString(
            if (isFav) R.string.showcase_action_fav_remove else R.string.showcase_action_fav_add
        )

        btnWatch.setOnClickListener {
            dialog.dismiss()
            onOpenTorrent(item)
        }
        btnFav.setOnClickListener {
            dialog.dismiss()
            onToggleFavoriteTorrent(item)
        }
        btnFindAll.setOnClickListener {
            dialog.dismiss()
            val cleanQuery = TorrentShowcaseClient.extractCleanSearchQuery(item.title)
            onSearchCleanQuery(cleanQuery)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        btnWatch.requestFocus()
    }

    fun showFavoritesDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_saved_list)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.80).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvSavedDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSavedDialogSubtitle)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnSavedClearAll)
        val btnClose = dialog.findViewById<TextView>(R.id.btnSavedClose)
        val rvList = dialog.findViewById<RecyclerView>(R.id.rvSavedList)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvSavedEmpty)

        tvTitle.text = activity.getString(R.string.saved_favorites_title)
        tvEmpty.text = activity.getString(R.string.saved_favorites_empty)

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
                onOpenTorrent(fav.toTorrentItem())
            },
            onItemRemove = { fav ->
                storage.removeFavoriteTorrent(fav.id)
                onRefreshFavoritesUi()
                Toast.makeText(activity, "Удалено из избранного", Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) renderModal()
            }
        )
        rvList.layoutManager = LinearLayoutManager(activity)
        rvList.adapter = modalAdapter

        btnClearAll.setOnClickListener {
            storage.clearFavoriteTorrents()
            onRefreshFavoritesUi()
            Toast.makeText(activity, "Избранное очищено", Toast.LENGTH_SHORT).show()
            if (dialog.isShowing) renderModal()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        renderModal()
        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        if (rvList.visibility == View.VISIBLE) {
            rvList.post {
                rvList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rvList.getChildAt(0)?.requestFocus()
            }
        } else {
            btnClose.requestFocus()
        }
    }

    fun showWatchlistDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_saved_list)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.80).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvSavedDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSavedDialogSubtitle)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnSavedClearAll)
        val btnClose = dialog.findViewById<TextView>(R.id.btnSavedClose)
        val rvList = dialog.findViewById<RecyclerView>(R.id.rvSavedList)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvSavedEmpty)

        tvTitle.text = activity.getString(R.string.saved_watchlist_title)
        tvEmpty.text = activity.getString(R.string.saved_watchlist_empty)

        lateinit var modalAdapter: WatchlistAdapter

        fun renderModal() {
            val items = applyCachedMeta(storage.getWatchlist())
            modalAdapter.submitList(items)
            onUpdateWatchlistButtonUi()
            if (items.isNotEmpty()) {
                tvSubtitle.text = "Запланировано фильмов: ${items.size} • OK — карточка, удерж. OK — убрать"
                rvList.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                btnClearAll.visibility = View.VISIBLE
                btnClearAll.nextFocusDownId = R.id.rvSavedList
                btnClose.nextFocusDownId = R.id.rvSavedList
            } else {
                tvSubtitle.text = "Запланировано фильмов: 0"
                rvList.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                btnClearAll.visibility = View.GONE
                btnClose.nextFocusDownId = R.id.btnSavedClose
                btnClose.requestFocus()
            }
        }

        modalAdapter = WatchlistAdapter(
            scope = uiScope,
            tmdbClient = tmdbClient,
            posterLoader = posterLoader,
            onItemClick = { movie ->
                dialog.dismiss()
                onOpenMovieDetails(movie)
            },
            onItemRemove = { movie ->
                showShelfActionDialog(
                    title = activity.getString(R.string.action_watchlist_title),
                    subtitle = movie.titleWithYear(),
                    deleteOneLabel = activity.getString(R.string.action_watchlist_delete_one),
                    clearAllLabel = activity.getString(R.string.action_watchlist_clear_all),
                    onDeleteOne = {
                        storage.removeFromWatchlist(movie)
                        onUpdateWatchlistButtonUi()
                        Toast.makeText(activity, "Убрано из «Буду смотреть»", Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) renderModal()
                    },
                    onClearAll = {
                        storage.clearWatchlist()
                        onUpdateWatchlistButtonUi()
                        Toast.makeText(activity, "Список «Буду смотреть» очищен", Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) renderModal()
                    }
                )
            }
        )
        rvList.layoutManager = LinearLayoutManager(activity)
        rvList.adapter = modalAdapter

        btnClearAll.setOnClickListener {
            storage.clearWatchlist()
            onUpdateWatchlistButtonUi()
            Toast.makeText(activity, "Список «Буду смотреть» очищен", Toast.LENGTH_SHORT).show()
            if (dialog.isShowing) renderModal()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        renderModal()
        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        if (rvList.visibility == View.VISIBLE) {
            rvList.post {
                rvList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rvList.getChildAt(0)?.requestFocus()
            }
        } else {
            btnClose.requestFocus()
        }
    }

    fun showHistoryDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_saved_list)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.80).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.82).toInt()
        )

        val tvTitle = dialog.findViewById<TextView>(R.id.tvSavedDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSavedDialogSubtitle)
        val btnClearAll = dialog.findViewById<TextView>(R.id.btnSavedClearAll)
        val btnClose = dialog.findViewById<TextView>(R.id.btnSavedClose)
        val rvList = dialog.findViewById<RecyclerView>(R.id.rvSavedList)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvSavedEmpty)

        tvTitle.text = activity.getString(R.string.saved_history_title)
        tvEmpty.text = activity.getString(R.string.saved_history_empty)

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
                onOpenHistoryEntry(torrentItem, entry)
            },
            onItemMenu = { entry ->
                val label = if (entry.episodeLabel.isNotBlank()) {
                    "${entry.title} — ${entry.episodeLabel}"
                } else {
                    entry.title
                }
                val isFav = storage.isFavoriteTorrent(TorrentItem(title = entry.title, magnet = entry.link))
                showShelfActionDialog(
                    title = activity.getString(R.string.action_history_title),
                    subtitle = label,
                    deleteOneLabel = activity.getString(R.string.action_history_delete_one),
                    clearAllLabel = activity.getString(R.string.action_history_clear_all),
                    extraActionLabel = activity.getString(
                        if (isFav) R.string.action_history_fav_remove else R.string.action_history_fav_add
                    ),
                    onExtraAction = {
                        onToggleFavoriteFromHistory(entry)
                        if (dialog.isShowing) renderModal()
                    },
                    onDeleteOne = {
                        storage.removeWatchHistoryItem(entry.id)
                        onRefreshHomeShelves()
                        Toast.makeText(activity, "Удалено из истории", Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) renderModal()
                    },
                    onClearAll = {
                        storage.clearWatchHistory()
                        onRefreshHomeShelves()
                        Toast.makeText(activity, "История просмотров очищена", Toast.LENGTH_SHORT).show()
                        if (dialog.isShowing) renderModal()
                    }
                )
            }
        )
        rvList.layoutManager = LinearLayoutManager(activity)
        rvList.adapter = modalHistoryAdapter

        btnClearAll.setOnClickListener {
            storage.clearWatchHistory()
            onRefreshHomeShelves()
            Toast.makeText(activity, "История просмотров очищена", Toast.LENGTH_SHORT).show()
            if (dialog.isShowing) renderModal()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        renderModal()
        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        if (rvList.visibility == View.VISIBLE) {
            rvList.post {
                rvList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: rvList.getChildAt(0)?.requestFocus()
            }
        } else {
            btnClose.requestFocus()
        }
    }
}
