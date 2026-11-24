package com.torplay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val onItemClick: (WatchHistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<WatchHistoryEntry>()

    fun submitList(newItems: List<WatchHistoryEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_torrent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvQuality: TextView = itemView.findViewById(R.id.tvQuality)
        private val tvHdr: TextView = itemView.findViewById(R.id.tvHdr)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvSeeds)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)

        fun bind(entry: WatchHistoryEntry) {
            tvTitle.text = entry.title
            tvQuality.text = "▶"
            tvQuality.setBackgroundResource(R.drawable.bg_badge_1080)
            tvHdr.visibility = View.GONE
            tvSize.text = entry.progressText()
            tvSeeds.text = ""
            tvSource.text = "Нажмите OK для продолжения"
            itemView.setOnClickListener { onItemClick(entry) }
        }
    }
}

class FavoriteAdapter(
    private val onItemClick: (FavoriteQuery) -> Unit
) : RecyclerView.Adapter<FavoriteAdapter.ViewHolder>() {

    private val items = mutableListOf<FavoriteQuery>()

    fun submitList(newItems: List<FavoriteQuery>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_torrent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvQuality: TextView = itemView.findViewById(R.id.tvQuality)
        private val tvHdr: TextView = itemView.findViewById(R.id.tvHdr)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvSeeds)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)

        fun bind(fav: FavoriteQuery) {
            tvTitle.text = fav.query
            tvQuality.text = "★"
            tvQuality.setBackgroundResource(R.drawable.bg_badge_hdr)
            tvHdr.visibility = View.GONE
            tvSize.text = ""
            tvSeeds.text = ""
            tvSource.text = "Быстрый поиск раздач"
            itemView.setOnClickListener { onItemClick(fav) }
        }
    }
}
