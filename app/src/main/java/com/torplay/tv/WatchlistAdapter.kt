package com.torplay.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class WatchlistAdapter(
    private val scope: CoroutineScope,
    private val tmdbClient: TmdbClient,
    private val posterLoader: PosterLoader,
    private val onItemClick: (CatalogItem) -> Unit,
    private val onItemRemove: (CatalogItem) -> Unit
) : RecyclerView.Adapter<WatchlistAdapter.ViewHolder>() {

    private val items = mutableListOf<CatalogItem>()

    fun submitList(newItems: List<CatalogItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watchlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivWatchlistPoster)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvWatchlistTitle)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvWatchlistMeta)
        private val tvRating: TextView = itemView.findViewById(R.id.tvWatchlistRating)

        fun bind(item: CatalogItem) {
            tvTitle.text = item.titleWithYear()

            val metaParts = mutableListOf<String>()
            metaParts.add(if (item.isSeries()) "Сериал" else "Фильм")
            val dur = item.formattedDuration()
            if (dur.isNotEmpty()) metaParts.add(dur)
            if (item.genreNames.isNotEmpty()) metaParts.add(item.genreNames.take(2).joinToString(", "))
            tvMeta.text = metaParts.joinToString(" • ")

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

            val posterUrl = tmdbClient.buildPosterUrl(item.posterPath)
            posterLoader.load(scope, ivPoster, posterUrl)

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemRemove(item)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onItemRemove(item)
                    true
                } else {
                    false
                }
            }
        }
    }
}
