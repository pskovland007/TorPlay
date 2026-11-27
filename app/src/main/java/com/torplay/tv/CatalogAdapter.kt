package com.torplay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class CatalogAdapter(
    private val scope: CoroutineScope,
    private val tmdbClient: TmdbClient,
    private val posterLoader: PosterLoader,
    private val compact: Boolean = false,
    private val onItemFocused: ((CatalogItem) -> Unit)? = null,
    private val onItemClick: (CatalogItem) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    private val items = mutableListOf<CatalogItem>()

    fun submitList(newItems: List<CatalogItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateItemMetadata(
        stableKey: String,
        runtimeMinutes: Int,
        bestQuality: String,
        bestSeeds: Int,
        bestPeers: Int
    ) {
        val idx = items.indexOfFirst { it.stableKey() == stableKey }
        if (idx < 0) return
        val cur = items[idx]
        val newRuntime = if (runtimeMinutes > 0) runtimeMinutes else cur.runtimeMinutes
        val newQuality = if (bestQuality.isNotBlank()) bestQuality else cur.bestQuality
        val newSeeds = if (bestSeeds > 0) bestSeeds else cur.bestSeeds
        val newPeers = if (bestPeers > 0) bestPeers else cur.bestPeers
        if (newRuntime == cur.runtimeMinutes &&
            newQuality == cur.bestQuality &&
            newSeeds == cur.bestSeeds &&
            newPeers == cur.bestPeers
        ) {
            return
        }
        items[idx] = cur.copy(
            runtimeMinutes = newRuntime,
            bestQuality = newQuality,
            bestSeeds = newSeeds,
            bestPeers = newPeers
        )
        notifyItemChanged(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutRes = if (compact) R.layout.item_catalog_compact else R.layout.item_catalog_poster
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivCatalogPoster)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvCatalogTitle)
        private val tvGenre: TextView = itemView.findViewById(R.id.tvCatalogGenre)
        private val tvRating: TextView = itemView.findViewById(R.id.tvCatalogRating)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvCatalogDuration)
        private val tvTorrentStats: TextView = itemView.findViewById(R.id.tvCatalogTorrentStats)

        fun bind(item: CatalogItem) {
            tvTitle.text = item.titleWithYear()

            val primaryGenre = item.primaryGenre()
            val genreText = when {
                item.isSeries() && primaryGenre.isNotBlank() -> "Сериал • $primaryGenre"
                item.isSeries() -> "Сериал"
                primaryGenre.isNotBlank() -> primaryGenre
                else -> "Фильм"
            }
            tvGenre.text = genreText

            val ratingStr = item.formattedRating()
            if (ratingStr.isNotEmpty()) {
                tvRating.text = ratingStr
                tvRating.visibility = View.VISIBLE
                val ratingColor = when {
                    item.rating >= 7.5 -> R.color.status_ok
                    item.rating >= 6.0 -> R.color.status_warn
                    else -> R.color.text_secondary
                }
                tvRating.setTextColor(ContextCompat.getColor(itemView.context, ratingColor))
            } else {
                tvRating.visibility = View.GONE
            }

            val durStr = item.formattedDuration()
            if (durStr.isNotEmpty()) {
                tvDuration.text = durStr
                tvDuration.visibility = View.VISIBLE
            } else {
                tvDuration.visibility = View.GONE
            }

            val statsStr = item.formattedTorrentStats()
            if (statsStr.isNotEmpty()) {
                tvTorrentStats.text = statsStr
                tvTorrentStats.visibility = View.VISIBLE
                val statsColor = when {
                    item.bestSeeds >= 10 -> R.color.status_ok
                    item.bestSeeds > 0 -> R.color.status_warn
                    else -> R.color.text_secondary
                }
                tvTorrentStats.setTextColor(ContextCompat.getColor(itemView.context, statsColor))
            } else {
                tvTorrentStats.visibility = View.GONE
            }

            val posterUrl = tmdbClient.buildPosterUrl(item.posterPath)
            posterLoader.load(scope, ivPoster, posterUrl)

            itemView.setOnClickListener {
                onItemClick(item)
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onItemFocused?.invoke(item)
                }
            }
        }
    }
}
