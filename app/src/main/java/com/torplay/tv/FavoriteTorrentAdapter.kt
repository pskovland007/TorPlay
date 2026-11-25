package com.torplay.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FavoriteTorrentAdapter(
    private val onItemClick: (FavoriteTorrent) -> Unit,
    private val onItemRemove: (FavoriteTorrent) -> Unit,
    private val onItemMenu: (FavoriteTorrent) -> Unit = onItemRemove
) : RecyclerView.Adapter<FavoriteTorrentAdapter.ViewHolder>() {

    private val items = mutableListOf<FavoriteTorrent>()

    fun submitList(newItems: List<FavoriteTorrent>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_torrent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvFavTorrentTitle)
        private val tvQuality: TextView = itemView.findViewById(R.id.tvFavQuality)
        private val tvHdr: TextView = itemView.findViewById(R.id.tvFavHdr)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvFavMeta)
        private val btnRemove: TextView = itemView.findViewById(R.id.btnFavRemove)

        fun bind(item: FavoriteTorrent) {
            tvTitle.text = item.title

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

            val parts = mutableListOf<String>()
            val sz = item.formattedSize()
            if (sz != "—") parts.add(sz)
            val src = item.formattedSource()
            if (src.isNotBlank()) parts.add(src)
            tvMeta.text = parts.joinToString(" • ").ifBlank { itemView.context.getString(R.string.fav_item_hint) }

            btnRemove.setOnClickListener { onItemRemove(item) }
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
