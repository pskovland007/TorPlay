package com.torplay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class TorrentAdapter(
    private val onItemClick: (TorrentItem) -> Unit
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
        private val tvQuality: TextView = itemView.findViewById(R.id.tvQuality)
        private val tvHdr: TextView = itemView.findViewById(R.id.tvHdr)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        private val tvSeeds: TextView = itemView.findViewById(R.id.tvSeeds)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)

        fun bind(item: TorrentItem) {
            tvTitle.text = item.title

            val qualityLabel = item.quality.ifBlank { "HD" }
            tvQuality.text = qualityLabel
            val badgeRes = when (qualityLabel) {
                "4K" -> R.drawable.bg_badge_4k
                "1080p" -> R.drawable.bg_badge_1080
                "720p" -> R.drawable.bg_badge_720
                else -> R.drawable.bg_badge_default
            }
            tvQuality.setBackgroundResource(badgeRes)

            tvHdr.visibility = if (item.isHdr) View.VISIBLE else View.GONE
            tvSize.text = item.formattedSize()
            tvSeeds.text = "▲ ${item.seeds}"

            val seedColor = when {
                item.seeds >= 15 -> R.color.status_ok
                item.seeds >= 3 -> R.color.status_warn
                else -> R.color.status_err
            }
            tvSeeds.setTextColor(ContextCompat.getColor(itemView.context, seedColor))

            tvSource.text = item.formattedSource()

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
