package com.torplay.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SmartEpisodeAdapter(
    private val files: List<TorrServeFile>,
    private val currentPlayingFileId: Int? = null,
    private val lastOpenedFileId: Int? = null,
    private val progressProvider: (Int) -> EpisodeProgress?,
    private val completedChecker: (Int) -> Boolean,
    private val onEpisodeClick: (TorrServeFile) -> Unit,
    private val onEpisodeToggleWatched: (TorrServeFile) -> Unit
) : RecyclerView.Adapter<SmartEpisodeAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode_smart, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position], position)
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIcon: TextView = itemView.findViewById(R.id.tvSmartEpIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvSmartEpName)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvSmartEpStatus)
        private val tvSize: TextView = itemView.findViewById(R.id.tvSmartEpSize)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressSmartEp)

        fun bind(file: TorrServeFile, position: Int) {
            val ctx = itemView.context
            tvName.text = file.fileName
            tvSize.text = file.formattedSize()

            val isPlayingNow = currentPlayingFileId != null && file.id == currentPlayingFileId
            val progress = progressProvider(file.id)
            val isCompleted = completedChecker(file.id)
            val hasPartialPos = progress != null && !isCompleted && progress.positionMs > 1_500L
            val isLastOpened = lastOpenedFileId != null && file.id == lastOpenedFileId && !isCompleted

            when {
                isPlayingNow -> {
                    tvIcon.text = "▶"
                    tvIcon.setTextColor(ContextCompat.getColor(ctx, R.color.accent_emerald))
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.accent_emerald))
                    val posStr = progress?.formattedPosition().orEmpty()
                    tvStatus.text = if (posStr.isNotBlank()) "Сейчас играет • $posStr" else "Сейчас играет"
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_emerald))
                    tvStatus.visibility = View.VISIBLE
                    val permille = progress?.progressPermille() ?: 0
                    if (permille > 0 && permille < 1000) {
                        progressBar.progress = permille
                        progressBar.visibility = View.VISIBLE
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
                hasPartialPos -> {
                    tvIcon.text = "▶"
                    tvIcon.setTextColor(ContextCompat.getColor(ctx, R.color.accent_emerald))
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    val posStr = progress!!.formattedPosition()
                    tvStatus.text = if (posStr.isNotBlank()) "Остановились: $posStr" else "Недосмотрено"
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.accent_emerald))
                    tvStatus.visibility = View.VISIBLE
                    val permille = progress.progressPermille()
                    if (permille > 0) {
                        progressBar.progress = permille
                        progressBar.visibility = View.VISIBLE
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
                isCompleted -> {
                    tvIcon.text = "✓"
                    tvIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_ok))
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    tvStatus.text = "Просмотрено"
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_ok))
                    tvStatus.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
                isLastOpened -> {
                    tvIcon.text = "▶"
                    tvIcon.setTextColor(ContextCompat.getColor(ctx, R.color.status_warn))
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    tvStatus.text = "Последняя открытая серия"
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_warn))
                    tvStatus.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                }
                else -> {
                    tvIcon.text = "${position + 1}"
                    tvIcon.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    tvName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    tvStatus.visibility = View.GONE
                    progressBar.visibility = View.GONE
                }
            }

            itemView.setOnClickListener {
                onEpisodeClick(file)
            }

            itemView.setOnLongClickListener {
                onEpisodeToggleWatched(file)
                true
            }

            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && keyCode == KeyEvent.KEYCODE_MENU) {
                    onEpisodeToggleWatched(file)
                    true
                } else {
                    false
                }
            }
        }
    }
}
