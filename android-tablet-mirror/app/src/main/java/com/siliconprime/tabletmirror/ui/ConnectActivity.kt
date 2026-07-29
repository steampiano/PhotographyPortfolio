package com.siliconprime.tabletmirror.ui

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

/**
 * Finds a host to control: pick a discovered one, or type an address when the
 * network blocks multicast discovery.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var browser: HostBrowser
    private val adapter = HostAdapter(::onHostChosen)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        browser.stop()
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
