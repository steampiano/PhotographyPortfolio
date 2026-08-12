package com.siliconprime.tabletmirror.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.databinding.ActivityConnectBinding
import com.siliconprime.tabletmirror.net.DiscoveredHost
import com.siliconprime.tabletmirror.net.HostBrowser
import com.siliconprime.tabletmirror.net.Protocol
import com.siliconprime.tabletmirror.viewer.Endpoint
import com.siliconprime.tabletmirror.viewer.RecentHost
import com.siliconprime.tabletmirror.viewer.ViewerPrefs

/**
 * Finds a host to control: pick one used before, pick one discovered on the
 * network, or type an address when the network blocks multicast discovery.
 *
 * Once a tablet has been paired this screen normally does not appear at all. It
 * forwards straight through to the last host, so opening the app is the only
 * action needed to get a picture back. Pass [pickIntent] to reach it deliberately
 * and choose something else — which is what every "switch tablet" route in the app
 * does, because a screen that always forwards is a screen you can never get to.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var prefs: ViewerPrefs
    private lateinit var browser: HostBrowser
    private val adapter = HostAdapter(::onHostChosen)

    /**
     * True when onCreate handed straight off to the viewer. Android still runs the
     * rest of the lifecycle after finish(), so the later callbacks must not touch
     * the fields this screen never got round to initialising.
     */
    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ViewerPrefs(this)

        // Straight back to the tablet we were driving, unless asked to choose.
        val remembered = prefs.lastEndpoint
        if (remembered != null && !intent.getBooleanExtra(EXTRA_PICK, false)) {
            forwarded = true
            startActivity(
                ViewerActivity.intent(this, remembered.address, remembered.port, pairing = false),
            )
            finish()
            return
        }

        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A visible arrow to go back, rather than relying on the system gesture
        // that someone new to Android has no way of guessing.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // The action bar already clears the status bar; this is about the task bar
        // along the bottom, which was covering the Connect button.
        SystemBars.pad(binding.scroll, top = false)

        binding.hostList.layoutManager = LinearLayoutManager(this)
        binding.hostList.adapter = adapter

        // Recents first, so the pairing instruction can override the generic hint.
        renderRecentHosts()
        prefill()

        binding.buttonConnect.setOnClickListener { connectManually() }

        browser = HostBrowser(this)
    }

    /**
     * Fills in whichever tablet the caller had in mind, falling back to the one used
     * last. Arriving here after being unpaired should not mean reading an address off
     * the other tablet and typing it back in.
     */
    private fun prefill() {
        val recent = prefs.recentHosts.firstOrNull()
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: recent?.address
        val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it in 1..65535 }
            ?: recent?.port
            ?: Protocol.DEFAULT_PORT

        binding.address.setText(address.orEmpty())
        binding.port.setText(port.toString())

        if (intent.getBooleanExtra(EXTRA_PAIRING, false)) {
            binding.pairMode.isChecked = true
            val name = recent?.name?.takeIf { it.isNotEmpty() } ?: address.orEmpty()
            binding.recentHint.text = getString(R.string.connect_pair_again_hint, name)
        }
    }

    private fun renderRecentHosts() {
        val hosts = prefs.recentHosts
        binding.recentList.removeAllViews()
        if (hosts.isEmpty()) {
            binding.recentHint.setText(R.string.connect_no_recent)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (host in hosts) {
            val row = inflater.inflate(R.layout.item_host, binding.recentList, false)
            row.findViewById<TextView>(R.id.host_name).text =
                host.name.ifEmpty { host.address }
            row.findViewById<TextView>(R.id.host_address).text = "${host.address}:${host.port}"
            row.setOnClickListener { onRecentChosen(host) }
            binding.recentList.addView(row)
        }
    }

    override fun onStart() {
        super.onStart()
        if (forwarded) return
        adapter.clear()
        browser.start(
            onFound = { host -> runOnUiThread { adapter.add(host) } },
            onError = { message ->
                runOnUiThread {
                    binding.discoveryHint.text = getString(R.string.connect_discovery_failed, message)
                }
            },
        )
    }

    override fun onStop() {
        if (!forwarded) browser.stop()
        super.onStop()
    }

    /** A tablet already paired with: no decisions left to make, so just go. */
    private fun onRecentChosen(host: RecentHost) {
        binding.address.setText(host.address)
        binding.port.setText(host.port.toString())
        if (binding.pairMode.isChecked) {
            // Re-pairing is an attended act; let the operator press Connect once the
            // other tablet is showing "ready to pair".
            return
        }
        connect(Endpoint(host.address, host.port), pairing = false)
    }

    /** A tablet found on the network may still need pairing, so only fill the fields. */
    private fun onHostChosen(host: DiscoveredHost) {
        binding.address.setText(host.address)
        binding.port.setText(host.port.toString())
    }

    private fun connectManually() {
        val address = binding.address.text?.toString()?.trim().orEmpty()
        val port = binding.port.text?.toString()?.toIntOrNull() ?: Protocol.DEFAULT_PORT

        when {
            address.isEmpty() -> showError(getString(R.string.connect_need_address))
            port !in 1..65535 -> showError(getString(R.string.connect_bad_port))
            else -> {
                binding.error.visibility = View.GONE
                connect(Endpoint(address, port), binding.pairMode.isChecked)
            }
        }
    }

    private fun connect(endpoint: Endpoint, pairing: Boolean) {
        // Pairing is only attempted when asked for. Without this, an impostor
        // answering on the host's address could provoke a pairing prompt during
        // ordinary use.
        //
        // This screen stays on the stack deliberately: leaving the viewer then lands
        // back on the chooser, which is where someone who has just disconnected wants
        // to be, rather than at the role picker two steps further out.
        startActivity(
            ViewerActivity.intent(this, endpoint.address, endpoint.port, pairing),
        )
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_PICK = "pick"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PAIRING = "pairing"

        /**
         * Opens the chooser even when a host is already remembered. [prefill] and
         * [pairing] carry the tablet the caller was already dealing with, so being
         * sent here from a failed session does not lose its address.
         */
        fun pickIntent(
            context: Context,
            prefill: Endpoint? = null,
            pairing: Boolean = false,
        ): Intent = Intent(context, ConnectActivity::class.java).apply {
            // Reuse the chooser's place in the stack rather than piling up a new one
            // each time somebody switches tablet.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_PICK, true)
            putExtra(EXTRA_PAIRING, pairing)
            prefill?.let {
                putExtra(EXTRA_ADDRESS, it.address)
                putExtra(EXTRA_PORT, it.port)
            }
        }
    }

    private class HostAdapter(
        private val onClick: (DiscoveredHost) -> Unit,
    ) : RecyclerView.Adapter<HostAdapter.ViewHolder>() {

        private val hosts = mutableListOf<DiscoveredHost>()

        fun add(host: DiscoveredHost) {
            // Discovery can report the same service more than once.
            if (hosts.any { it.address == host.address && it.port == host.port }) return
            hosts.add(host)
            notifyItemInserted(hosts.lastIndex)
        }

        fun clear() {
            val size = hosts.size
            hosts.clear()
            notifyItemRangeRemoved(0, size)
        }

        override fun getItemCount(): Int = hosts.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_host, parent, false)
            return ViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(hosts[position])
        }

        class ViewHolder(
            itemView: View,
            private val onClick: (DiscoveredHost) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.host_name)
            private val subtitle: TextView = itemView.findViewById(R.id.host_address)

            fun bind(host: DiscoveredHost) {
                title.text = host.serviceName
                subtitle.text = "${host.address}:${host.port}"
                itemView.setOnClickListener { onClick(host) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
