package com.torplay.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WifiSyncDialogHelper(
    private val activity: Activity,
    private val storage: StorageManager,
    private val wifiSyncManager: WifiSyncManager,
    private val onSyncCompleted: () -> Unit
) {

    fun showWifiSyncDialog() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_wifi_sync)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.78).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.84).toInt()
        )

        val btnScan = dialog.findViewById<TextView>(R.id.btnWifiSyncScan)
        val btnClose = dialog.findViewById<TextView>(R.id.btnWifiSyncClose)
        val tvLocalInfo = dialog.findViewById<TextView>(R.id.tvWifiSyncLocalInfo)
        val rvPeers = dialog.findViewById<RecyclerView>(R.id.rvWifiPeers)
        val layoutStatus = dialog.findViewById<LinearLayout>(R.id.layoutWifiSyncStatus)
        val progressSync = dialog.findViewById<ProgressBar>(R.id.progressWifiSync)
        val tvStatus = dialog.findViewById<TextView>(R.id.tvWifiSyncStatus)
        val tvResult = dialog.findViewById<TextView>(R.id.tvWifiSyncResult)
        val etManualIp = dialog.findViewById<EditText>(R.id.etWifiSyncIp)
        val btnManualSync = dialog.findViewById<TextView>(R.id.btnWifiSyncManual)

        val localIp = wifiSyncManager.getLocalIpv4Address()
        tvLocalInfo.text = if (localIp.isNotBlank()) {
            "Это устройство: ${wifiSyncManager.deviceName}  •  IP: $localIp"
        } else {
            "Это устройство: ${wifiSyncManager.deviceName}  •  Wi-Fi не подключён"
        }
        etManualIp.setText(storage.lastWifiSyncIp)

        var scanJob: Job? = null
        var syncJob: Job? = null
        val discoveredPeers = mutableListOf<WifiSyncManager.DiscoveredPeer>()

        fun hideDialogKeyboard() {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val token = dialog.currentFocus?.windowToken ?: dialog.window?.decorView?.windowToken
            if (token != null) {
                imm?.hideSoftInputFromWindow(token, 0)
            }
        }

        fun updateFocusRoutes(hasPeers: Boolean) {
            if (hasPeers) {
                btnScan.nextFocusDownId = R.id.rvWifiPeers
                btnClose.nextFocusDownId = R.id.rvWifiPeers
                etManualIp.nextFocusUpId = R.id.rvWifiPeers
                btnManualSync.nextFocusUpId = R.id.rvWifiPeers
            } else {
                btnScan.nextFocusDownId = R.id.etWifiSyncIp
                btnClose.nextFocusDownId = R.id.btnWifiSyncManual
                etManualIp.nextFocusUpId = R.id.btnWifiSyncScan
                btnManualSync.nextFocusUpId = R.id.btnWifiSyncClose
            }
        }

        fun runSyncWith(ip: String, port: Int = WifiSyncManager.HTTP_PORT) {
            if (syncJob?.isActive == true) return
            hideDialogKeyboard()
            scanJob?.cancel()

            tvResult.visibility = View.GONE
            rvPeers.visibility = View.GONE
            layoutStatus.visibility = View.VISIBLE
            progressSync.visibility = View.VISIBLE
            tvStatus.text = activity.getString(R.string.wifi_sync_progress)
            btnScan.isEnabled = false
            btnManualSync.isEnabled = false

            syncJob = dialogScope.launch {
                val res = wifiSyncManager.syncWithPeer(ip, port)
                if (!dialog.isShowing) return@launch
                btnScan.isEnabled = true
                btnManualSync.isEnabled = true
                progressSync.visibility = View.GONE

                tvResult.visibility = View.VISIBLE
                tvResult.setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (res.ok) R.color.status_ok else R.color.status_err
                    )
                )
                tvResult.text = if (res.ok) "✓ ${res.message}" else "✕ ${res.message}"

                if (res.ok) {
                    etManualIp.setText(storage.lastWifiSyncIp)
                    onSyncCompleted()
                    Toast.makeText(activity, "Синхронизация завершена", Toast.LENGTH_SHORT).show()
                }

                if (discoveredPeers.isNotEmpty()) {
                    layoutStatus.visibility = View.GONE
                    rvPeers.visibility = View.VISIBLE
                    updateFocusRoutes(true)
                } else {
                    layoutStatus.visibility = View.VISIBLE
                    tvStatus.text = if (res.ok) {
                        "Данные объединены на обоих устройствах."
                    } else {
                        activity.getString(R.string.wifi_sync_empty)
                    }
                    updateFocusRoutes(false)
                }
                btnClose.requestFocus()
            }
        }

        val peerAdapter = WifiPeerAdapter { peer ->
            runSyncWith(peer.ip, peer.port)
        }
        rvPeers.layoutManager = LinearLayoutManager(activity)
        rvPeers.adapter = peerAdapter

        fun startDiscovery() {
            if (syncJob?.isActive == true) return
            scanJob?.cancel()
            discoveredPeers.clear()
            peerAdapter.submitList(emptyList())
            rvPeers.visibility = View.GONE
            layoutStatus.visibility = View.VISIBLE
            progressSync.visibility = View.VISIBLE
            tvStatus.text = activity.getString(R.string.wifi_sync_scanning)
            updateFocusRoutes(false)

            scanJob = dialogScope.launch {
                val all = wifiSyncManager.discoverPeers { peer ->
                    if (!dialog.isShowing) return@discoverPeers
                    if (discoveredPeers.none { it.ip == peer.ip }) {
                        discoveredPeers.add(peer)
                        peerAdapter.submitList(discoveredPeers.toList())
                        layoutStatus.visibility = View.GONE
                        rvPeers.visibility = View.VISIBLE
                        updateFocusRoutes(true)
                        if (discoveredPeers.size == 1) {
                            rvPeers.post {
                                rvPeers.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                    ?: rvPeers.getChildAt(0)?.requestFocus()
                            }
                        }
                    }
                }
                if (!dialog.isShowing) return@launch
                progressSync.visibility = View.GONE
                if (all.isNotEmpty()) {
                    discoveredPeers.clear()
                    discoveredPeers.addAll(all)
                    peerAdapter.submitList(all)
                    layoutStatus.visibility = View.GONE
                    rvPeers.visibility = View.VISIBLE
                    updateFocusRoutes(true)
                } else {
                    rvPeers.visibility = View.GONE
                    layoutStatus.visibility = View.VISIBLE
                    tvStatus.text = activity.getString(R.string.wifi_sync_empty)
                    updateFocusRoutes(false)
                }
            }
        }

        btnScan.setOnClickListener {
            hideDialogKeyboard()
            startDiscovery()
        }

        btnManualSync.setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            if (ip.isBlank()) {
                Toast.makeText(activity, "Введите IP второго устройства", Toast.LENGTH_SHORT).show()
                etManualIp.requestFocus()
                return@setOnClickListener
            }
            runSyncWith(ip)
        }

        etManualIp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideDialogKeyboard()
                btnManualSync.requestFocus()
                true
            } else {
                false
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            scanJob?.cancel()
            syncJob?.cancel()
            dialogScope.cancel()
        }

        dialog.show()
        SystemUiHelper.applyImmersiveToDialog(dialog)
        btnScan.requestFocus()
        startDiscovery()
    }

    private class WifiPeerAdapter(
        private val onPeerClick: (WifiSyncManager.DiscoveredPeer) -> Unit
    ) : RecyclerView.Adapter<WifiPeerAdapter.ViewHolder>() {

        private val items = mutableListOf<WifiSyncManager.DiscoveredPeer>()

        fun submitList(newItems: List<WifiSyncManager.DiscoveredPeer>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_wifi_peer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tvPeerName)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvPeerMeta)

            fun bind(peer: WifiSyncManager.DiscoveredPeer) {
                val verPart = if (peer.version.isNotBlank()) "  (v${peer.version})" else ""
                tvName.text = "${peer.deviceName}$verPart"
                tvMeta.text = peer.metaLine()
                itemView.setOnClickListener { onPeerClick(peer) }
            }
        }
    }
}
