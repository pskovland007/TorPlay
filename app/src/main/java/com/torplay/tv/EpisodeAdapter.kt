package com.torplay.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EpisodeAdapter(
    private val files: List<TorrServeFile>,
    private val watchedChecker: (Int) -> Boolean,
    private val onEpisodeClick: (TorrServeFile) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position], position)
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvWatched: TextView = itemView.findViewById(R.id.tvEpisodeWatched)
        private val tvName: TextView = itemView.findViewById(R.id.tvEpisodeName)
        private val tvSize: TextView = itemView.findViewById(R.id.tvEpisodeSize)

        fun bind(file: TorrServeFile, position: Int) {
            val isWatched = watchedChecker(file.id)
            tvWatched.visibility = if (isWatched) View.VISIBLE else View.INVISIBLE
            tvName.text = "${position + 1}. ${file.fileName}"
            tvSize.text = file.formattedSize()

            itemView.setOnClickListener {
                onEpisodeClick(file)
            }
        }
    }
}
