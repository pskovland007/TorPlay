package com.torplay.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val onItemClick: (WatchHistoryEntry) -> Unit,
    private val onItemMenu: (WatchHistoryEntry) -> Unit
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
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        private val progressWatch: ProgressBar = itemView.findViewById(R.id.progressWatch)

        fun bind(entry: WatchHistoryEntry) {
            tvTitle.text = entry.title
            tvQuality.text = "▶"
            tvQuality.setBackgroundResource(R.drawable.bg_badge_1080)
            tvHdr.visibility = View.GONE
            tvSize.text = entry.progressText()
            tvSize.visibility = View.VISIBLE
            tvSeeds.visibility = View.GONE
            tvDate.visibility = View.GONE
            tvSource.text = itemView.context.getString(R.string.shelf_hint_history)

            val permille = entry.progressPermille()
            if (permille > 0) {
                progressWatch.progress = permille
                progressWatch.visibility = View.VISIBLE
            } else {
                progressWatch.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(entry) }
            itemView.setOnLongClickListener {
                onItemMenu(entry)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onItemMenu(entry)
                    true
                } else {
                    false
                }
            }
        }
    }
}

class FavoriteAdapter(
    private val onItemClick: (FavoriteQuery) -> Unit,
    private val onItemMenu: (FavoriteQuery) -> Unit
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
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        private val progressWatch: ProgressBar = itemView.findViewById(R.id.progressWatch)

        fun bind(fav: FavoriteQuery) {
            tvTitle.text = fav.query
            tvQuality.text = "★"
            tvQuality.setBackgroundResource(R.drawable.bg_badge_hdr)
            tvHdr.visibility = View.GONE
            tvSize.visibility = View.GONE
            tvSeeds.visibility = View.GONE
            tvDate.visibility = View.GONE
            tvSource.text = itemView.context.getString(R.string.shelf_hint_favorite)
            progressWatch.visibility = View.GONE

            itemView.setOnClickListener { onItemClick(fav) }
            itemView.setOnLongClickListener {
                onItemMenu(fav)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onItemMenu(fav)
                    true
                } else {
                    false
                }
            }
        }
    }
}

class RecentSearchAdapter(
    private val onItemClick: (String) -> Unit,
    private val onItemMenu: (String) -> Unit
) : RecyclerView.Adapter<RecentSearchAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvChip: TextView = itemView.findViewById(R.id.tvRecentChip)

        fun bind(query: String) {
            tvChip.text = "↺ $query"
            itemView.setOnClickListener { onItemClick(query) }
            itemView.setOnLongClickListener {
                onItemMenu(query)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onItemMenu(query)
                    true
                } else {
                    false
                }
            }
        }
    }
}
