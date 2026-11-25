package com.torplay.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class TorrentShowcaseAdapter(
    private val isFavorite: (TorrentItem) -> Boolean,
    private val onItemClick: (TorrentItem) -> Unit,
    private val onItemMenu: (TorrentItem) -> Unit
) : RecyclerView.Adapter<TorrentShowcaseAdapter.ViewHolder>() {

    private val items = mutableListOf<TorrentItem>()

    fun submitList(newItems: List<TorrentItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_torrent_showcase_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvQuality: TextView = itemView.findViewById(R.id.tvShowcaseQuality)
        private val tvHdr: TextView = itemView.findViewById(R.id.tvShowcaseHdr)
        private val tvFavMark: TextView = itemView.findViewById(R.id.tvShowcaseFavMark)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvShowcaseSeeds)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvShowcaseTitle)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvShowcaseMeta)

        fun bind(item: TorrentItem) {
            tvTitle.text = item.title
            tvSeeds.text = "▲ ${item.seeds}"

            val seedsColor = when {
                item.seeds >= 10 -> R.color.status_ok
                item.seeds > 0 -> R.color.status_warn
                else -> R.color.status_err
            }
            tvSeeds.setTextColor(ContextCompat.getColor(itemView.context, seedsColor))

            if (item.quality.isNotBlank()) {
                tvQuality.text = item.quality
                tvQuality.visibility = View.VISIBLE
                val bg = when (item.quality) {
                    "4K" -> R.drawable.bg_badge_4k
                    "1080p" -> R.drawable.bg_badge_1080
                    "720p" -> R.drawable.bg_badge_720
                    else -> R.drawable.bg_badge_default
                }
                tvQuality.setBackgroundResource(bg)
            } else {
                tvQuality.visibility = View.GONE
            }

            tvHdr.visibility = if (item.isHdr) View.VISIBLE else View.GONE
            tvFavMark.visibility = if (isFavorite(item)) View.VISIBLE else View.GONE

            val parts = mutableListOf<String>()
            val sz = item.formattedSize()
            if (sz != "—") parts.add(sz)
            val dt = item.formattedDate()
            if (dt.isNotBlank()) parts.add(dt)
            if (item.tracker.isNotBlank()) parts.add(item.tracker)
            tvMeta.text = parts.joinToString(" • ")

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemMenu(item)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onItemMenu(item)
                    true
                } else {
                    false
                }
            }
        }
    }
}
