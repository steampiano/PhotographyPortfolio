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
import com.siliconprime.tabletmirror.viewer.ViewerPrefs

/**
 * Finds a host to control: pick a discovered one, or type an address when the
 * network blocks multicast discovery.
 *
 * Once a tablet has been paired this screen normally does not appear at all. It
 * forwards straight through to the last host, so opening the app is the only
 * action needed to get a picture back. Pass [pickIntent] to reach it deliberately
 * and choose something else.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
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

        // Straight back to the tablet we were driving, unless asked to choose.
        val remembered = ViewerPrefs(this).lastEndpoint
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

        binding.hostList.layoutManager = LinearLayoutManager(this)
        binding.hostList.adapter = adapter

        binding.port.setText(Protocol.DEFAULT_PORT.toString())
        binding.buttonConnect.setOnClickListener { connectManually() }

        browser = HostBrowser(this)
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
                // Pairing is only attempted when asked for. Without this, an
                // impostor answering on the host's address could provoke a pairing
                // prompt during ordinary use.
                startActivity(
                    ViewerActivity.intent(
                        context = this,
                        address = address,
                        port = port,
                        pairing = binding.pairMode.isChecked,
                    ),
                )
            }
        }
    }

    private fun showError(message: String) {
        binding.error.text = message
        binding.error.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_PICK = "pick"

        /** Opens the chooser even when a host is already remembered. */
        fun pickIntent(context: Context): Intent =
            Intent(context, ConnectActivity::class.java).putExtra(EXTRA_PICK, true)
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
}
