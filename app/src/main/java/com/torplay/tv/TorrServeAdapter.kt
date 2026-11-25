package com.torplay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TorrServeAdapter(
    private val onRemoveClick: (TorrServeInfo) -> Unit
) : RecyclerView.Adapter<TorrServeAdapter.ViewHolder>() {

    private val items = mutableListOf<TorrServeInfo>()

    fun submitList(newItems: List<TorrServeInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_torrserve_torrent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTsItemTitle)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvTsItemStatus)
        private val tvStats: TextView = itemView.findViewById(R.id.tvTsItemStats)

        fun bind(item: TorrServeInfo) {
            tvTitle.text = item.title.ifBlank { item.hash.take(24) }

            val statusLabel = item.statString.ifBlank { "В памяти" }
            tvStatus.text = statusLabel
            val badgeRes = when (item.stat) {
                3 -> R.drawable.bg_badge_1080
                2 -> R.drawable.bg_badge_hdr
                else -> R.drawable.bg_badge_default
            }
            tvStatus.setBackgroundResource(badgeRes)

            val parts = mutableListOf<String>()
            val sizeText = item.formattedTorrentSize()
            if (sizeText != "—") parts.add(sizeText)
            if (item.files.isNotEmpty()) parts.add("файлов: ${item.files.size}")
            if (item.downloadSpeed > 0.0) parts.add(item.formattedSpeed())
            if (item.totalPeers > 0 || item.activePeers > 0) {
                parts.add("пиры ${item.activePeers}/${item.totalPeers} (сиды ${item.connectedSeeders})")
            }
            tvStats.text = parts.joinToString(" • ").ifBlank { item.hash.take(16) }

            itemView.setOnClickListener {
                onRemoveClick(item)
            }
        }
    }
}
