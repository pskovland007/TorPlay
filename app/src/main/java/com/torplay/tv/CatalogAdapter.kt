package com.torplay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

class CatalogAdapter(
    private val scope: CoroutineScope,
    private val tmdbClient: TmdbClient,
    private val posterLoader: PosterLoader,
    private val onItemClick: (CatalogItem) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.ViewHolder>() {

    private val items = mutableListOf<CatalogItem>()

    fun submitList(newItems: List<CatalogItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivPoster)
        private val tvRating: TextView = itemView.findViewById(R.id.tvPosterRating)
        private val tvYear: TextView = itemView.findViewById(R.id.tvPosterYear)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPosterTitle)

        fun bind(item: CatalogItem) {
            tvTitle.text = item.title

            val ratingStr = item.formattedRating()
            if (ratingStr.isNotEmpty()) {
                tvRating.text = ratingStr
                tvRating.visibility = View.VISIBLE
            } else {
                tvRating.visibility = View.GONE
            }

            if (item.year.isNotBlank()) {
                tvYear.text = item.year
                tvYear.visibility = View.VISIBLE
            } else {
                tvYear.visibility = View.GONE
            }

            val posterUrl = tmdbClient.buildPosterUrl(item.posterPath)
            posterLoader.load(scope, ivPoster, posterUrl)

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
