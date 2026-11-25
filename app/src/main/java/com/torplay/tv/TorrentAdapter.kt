package com.torplay.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class TorrentAdapter(
    private val isFavorite: (TorrentItem) -> Boolean = { false },
    private val onItemClick: (TorrentItem) -> Unit,
    private val onToggleFavorite: (TorrentItem) -> Unit = {}
) : RecyclerView.Adapter<TorrentAdapter.ViewHolder>() {

    private val items = mutableListOf<TorrentItem>()

    fun submitList(newItems: List<TorrentItem>) {
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
        private val tvBadgeQuality: TextView = itemView.findViewById(R.id.tvQuality)
        private val tvBadgeHdr: TextView = itemView.findViewById(R.id.tvHdr)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvSeeds)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val btnFav: TextView = itemView.findViewById(R.id.btnTorrentFav)
        private val progressWatch: ProgressBar = itemView.findViewById(R.id.progressWatch)

        fun bind(item: TorrentItem) {
            progressWatch.visibility = View.GONE
            btnFav.visibility = View.VISIBLE

            tvTitle.text = item.title
            tvSize.text = item.formattedSize()
            tvSeeds.text = "▲ ${item.seeds}"

            val seedsColor = when {
                item.seeds >= 10 -> R.color.status_ok
                item.seeds > 0 -> R.color.status_warn
                else -> R.color.status_err
            }
            tvSeeds.setTextColor(ContextCompat.getColor(itemView.context, seedsColor))

            val dateStr = item.formattedDate()
            if (dateStr.isNotBlank()) {
                tvDate.text = dateStr
                tvDate.visibility = View.VISIBLE
            } else {
                tvDate.visibility = View.GONE
            }

            if (item.quality.isNotBlank()) {
                tvBadgeQuality.text = item.quality
                tvBadgeQuality.visibility = View.VISIBLE
                val bg = when (item.quality) {
                    "4K" -> R.drawable.bg_badge_4k
                    "1080p" -> R.drawable.bg_badge_1080
                    "720p" -> R.drawable.bg_badge_720
                    else -> R.drawable.bg_badge_default
                }
                tvBadgeQuality.setBackgroundResource(bg)
            } else {
                tvBadgeQuality.visibility = View.GONE
            }

            tvBadgeHdr.visibility = if (item.isHdr) View.VISIBLE else View.GONE
            tvSource.text = item.formattedSource()

            fun renderStar() {
                val fav = isFavorite(item)
                btnFav.text = if (fav) "★" else "☆"
                btnFav.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        if (fav) R.color.accent_emerald else R.color.text_secondary
                    )
                )
            }
            renderStar()

            btnFav.setOnClickListener {
                onToggleFavorite(item)
                renderStar()
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }

            itemView.setOnLongClickListener {
                onToggleFavorite(item)
                renderStar()
                true
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onToggleFavorite(item)
                    renderStar()
                    true
                } else {
                    false
                }
            }
        }
    }
}
