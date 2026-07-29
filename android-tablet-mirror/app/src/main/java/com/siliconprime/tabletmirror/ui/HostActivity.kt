package com.siliconprime.tabletmirror.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.crypto.PairedPeer
import com.siliconprime.tabletmirror.crypto.PreferencesTrustStore
import com.siliconprime.tabletmirror.databinding.ActivityHostBinding
import com.siliconprime.tabletmirror.host.ConnectionLog
import com.siliconprime.tabletmirror.host.HostSettings
import com.siliconprime.tabletmirror.host.HostState
import com.siliconprime.tabletmirror.host.MirrorAccessibilityService
import com.siliconprime.tabletmirror.host.Quality
import com.siliconprime.tabletmirror.host.ScreenCaptureService
import com.siliconprime.tabletmirror.net.PairingGate
import com.siliconprime.tabletmirror.net.Protocol
import kotlinx.coroutines.launch

/**
 * Sets up and monitors sharing *this* tablet's screen.
 *
 * Pairing is an explicit, time-limited act here rather than a standing
 * invitation: outside the window an unknown tablet is refused before any key
 * agreement happens.
 */
class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private lateinit var trustStore: PreferencesTrustStore
    private lateinit var settings: HostSettings
    private lateinit var log: ConnectionLog

    private var pairingDialog: AlertDialog? = null

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            showMessage(getString(R.string.host_permission_declined))
            return@registerForActivityResult
        }
        startSharing(result.resultCode, data)
    }

    private val notificationRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The service runs either way; without it the ongoing notice is hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        trustStore = PreferencesTrustStore(this)
        settings = HostSettings(this)
        log = ConnectionLog(this)

        binding.qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Quality.entries.map { getString(qualityLabel(it)) },
        )
        val remembered = settings.quality?.let { name ->
            Quality.entries.firstOrNull { it.name == name }
        } ?: Quality.BALANCED
        binding.qualitySpinner.setSelection(remembered.ordinal)
        binding.port.setText(settings.port.toString())

        binding.allowControl.isChecked = settings.controlAllowed
        binding.allowControl.setOnCheckedChangeListener { _, checked ->
            settings.controlAllowed = checked
            // Take effect on a live session immediately, and tell the viewer.
            ScreenCaptureService.notifyControlPolicyChanged(this)
            renderControlPolicy(checked)
        }

        binding.buttonStart.setOnClickListener { requestProjection() }
        binding.buttonStop.setOnClickListener {
            startService(ScreenCaptureService.stopIntent(this))
        }
        binding.buttonPair.setOnClickListener { openPairingWindow() }
        binding.buttonEnableControl.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        binding.buttonViewLog.setOnClickListener { showConnectionLog() }

        lifecycleScope.launch { ScreenCaptureService.state.collect(::render) }
        lifecycleScope.launch {
            ScreenCaptureService.pairingGate.pending.collect(::renderPairingRequest)
        }
        lifecycleScope.launch {
            ScreenCaptureService.pairingGate.windowClosesAt.collect { renderPairingWindow() }
        }
    }

    override fun onResume() {
        super.onResume()
        renderPairedDevices()
        renderAccessibility(MirrorAccessibilityService.isEnabledInSettings(this))
        renderControlPolicy(settings.controlAllowed)
    }

    // -----------------------------------------------------------------------
    // Sharing
    // -----------------------------------------------------------------------

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionRequest.launch(manager.createScreenCaptureIntent())
    }

    private fun startSharing(resultCode: Int, data: Intent) {
        val port = binding.port.text?.toString()?.toIntOrNull() ?: Protocol.DEFAULT_PORT
        if (port !in 1024..65535) {
            showMessage(getString(R.string.host_bad_port))
            return
        }
        val quality = Quality.entries.getOrElse(binding.qualitySpinner.selectedItemPosition) {
            Quality.BALANCED
        }
        // Remember the choices so the next start needs no decisions.
        settings.quality = quality.name
        settings.port = port
        startForegroundService(
            ScreenCaptureService.startIntent(this, resultCode, data, port, quality),
        )
    }

    // -----------------------------------------------------------------------
    // Pairing
    // -----------------------------------------------------------------------

    private fun openPairingWindow() {
        if (!ScreenCaptureService.state.value.sharing) {
            showMessage(getString(R.string.host_pair_needs_sharing))
            return
        }
        ScreenCaptureService.pairingGate.openWindow()
        renderPairingWindow()
    }

    private fun renderPairingWindow() {
        val gate = ScreenCaptureService.pairingGate
        val remaining = gate.remainingWindowMs()
        if (remaining <= 0) {
            binding.pairingStatus.visibility = View.GONE
            binding.buttonPair.setText(R.string.action_pair_device)
            return
        }
        binding.pairingStatus.visibility = View.VISIBLE
        binding.pairingStatus.text =
            getString(R.string.host_pairing_open, (remaining / 1000).toInt())
        binding.buttonPair.setText(R.string.action_pairing_open)
        // The window is short, so refresh the countdown while it lasts.
        binding.pairingStatus.postDelayed({ renderPairingWindow() }, 1000)
    }

    private fun renderPairingRequest(request: PairingGate.Request?) {
        if (request == null) {
            pairingDialog?.dismiss()
            pairingDialog = null
            renderPairedDevices()
            return
        }
        if (pairingDialog?.isShowing == true) return
        pairingDialog = PairingDialog.show(this, request, ScreenCaptureService.pairingGate)
    }

    private fun renderPairedDevices() {
        val peers = trustStore.all()
        binding.pairedDevices.text = if (peers.isEmpty()) {
            getString(R.string.host_no_paired_devices)
        } else {
            peers.joinToString("\n") { "${it.name}\n  ${it.fingerprint}" }
        }
        binding.buttonUnpair.visibility = if (peers.isEmpty()) View.GONE else View.VISIBLE
        binding.buttonUnpair.setOnClickListener { confirmUnpair(peers) }
    }

    private fun confirmUnpair(peers: List<PairedPeer>) {
        val labels = peers.map { "${it.name} — ${it.fingerprint}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.title_unpair)
            .setItems(labels) { _, index ->
                val peer = peers[index]
                trustStore.forget(peer.publicKey)
                log.record(ConnectionLog.Event.UNPAIRED, "${peer.name} (${peer.fingerprint})")
                renderPairedDevices()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun render(state: HostState) {
        val sharing = state.sharing
        binding.buttonStart.isEnabled = !sharing
        binding.buttonStop.isEnabled = sharing
        binding.qualitySpinner.isEnabled = !sharing
        binding.port.isEnabled = !sharing

        if (sharing) {
            binding.addresses.text = if (state.addresses.isEmpty()) {
                getString(R.string.host_no_addresses)
            } else {
                state.addresses.joinToString("\n") { "$it:${state.port}" }
            }
            binding.addressesLabel.visibility = View.VISIBLE
            binding.addresses.visibility = View.VISIBLE
            binding.clientStatus.text = state.clientName?.let {
                getString(R.string.host_client_connected, it)
            } ?: getString(R.string.host_waiting)
            binding.identity.visibility = View.VISIBLE
            binding.identity.text = getString(
                if (state.hardwareBackedKey) {
                    R.string.host_identity_hardware
                } else {
                    R.string.host_identity_software
                },
                state.fingerprint,
            )
        } else {
            binding.addressesLabel.visibility = View.GONE
            binding.addresses.visibility = View.GONE
            binding.identity.visibility = View.GONE
            binding.clientStatus.text = getString(R.string.host_idle)
        }

        showMessage(state.message)
    }

    private fun renderAccessibility(enabled: Boolean) {
        binding.controlStatus.setText(
            if (enabled) R.string.host_control_enabled else R.string.host_control_disabled,
        )
        binding.buttonEnableControl.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun renderControlPolicy(allowed: Boolean) {
        binding.controlPolicyHint.setText(
            if (allowed) R.string.host_control_allowed_hint else R.string.host_control_blocked_hint,
        )
    }

    private fun showConnectionLog() {
        val entries = log.recent()
        val text = if (entries.isEmpty()) {
            getString(R.string.host_log_empty)
        } else {
            entries.joinToString("\n") { "${it.timestamp}  ${it.event}  ${it.detail}" }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_connection_log)
            .setMessage(text)
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.action_clear_log) { _, _ -> log.clear() }
            .show()
    }

    private fun showMessage(message: String?) {
        binding.message.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (!message.isNullOrEmpty()) binding.message.text = message
    }

    private fun qualityLabel(quality: Quality): Int = when (quality) {
        Quality.LOW -> R.string.quality_low
        Quality.BALANCED -> R.string.quality_balanced
        Quality.HIGH -> R.string.quality_high
        Quality.NATIVE -> R.string.quality_native
    }
}
