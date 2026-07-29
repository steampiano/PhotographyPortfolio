package com.siliconprime.tabletmirror.ui

import android.Manifest
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
import com.siliconprime.tabletmirror.databinding.ActivityHostBinding
import com.siliconprime.tabletmirror.host.HostState
import com.siliconprime.tabletmirror.host.MirrorAccessibilityService
import com.siliconprime.tabletmirror.host.Quality
import com.siliconprime.tabletmirror.host.ScreenCaptureService
import com.siliconprime.tabletmirror.net.Handshake
import com.siliconprime.tabletmirror.net.Protocol
import kotlinx.coroutines.launch

/**
 * Sets up and monitors sharing *this* tablet's screen.
 *
 * The PIN is regenerated for every session rather than being a settable secret:
 * a short PIN is only safe while it is short-lived.
 */
class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private var pin: String = Handshake.randomPin()

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            binding.message.text = getString(R.string.host_permission_declined)
            binding.message.visibility = View.VISIBLE
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

        binding.qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Quality.entries.map { getString(qualityLabel(it)) },
        )
        binding.qualitySpinner.setSelection(Quality.BALANCED.ordinal)

        binding.port.setText(Protocol.DEFAULT_PORT.toString())
        binding.pin.text = formatPin(pin)

        binding.buttonRegeneratePin.setOnClickListener {
            pin = Handshake.randomPin()
            binding.pin.text = formatPin(pin)
        }
        binding.buttonStart.setOnClickListener { requestProjection() }
        binding.buttonStop.setOnClickListener {
            startService(ScreenCaptureService.stopIntent(this))
        }
        binding.buttonEnableControl.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        lifecycleScope.launch {
            ScreenCaptureService.state.collect(::render)
        }
    }

    override fun onResume() {
        super.onResume()
        renderControlAvailability(MirrorAccessibilityService.isEnabledInSettings(this))
    }

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
            binding.message.text = getString(R.string.host_bad_port)
            binding.message.visibility = View.VISIBLE
            return
        }
        val quality = Quality.entries.getOrElse(binding.qualitySpinner.selectedItemPosition) {
            Quality.BALANCED
        }
        startForegroundService(
            ScreenCaptureService.startIntent(this, resultCode, data, pin, port, quality),
        )
    }

    private fun render(state: HostState) {
        val sharing = state.sharing
        binding.buttonStart.isEnabled = !sharing
        binding.buttonStop.isEnabled = sharing
        binding.buttonRegeneratePin.isEnabled = !sharing
        binding.qualitySpinner.isEnabled = !sharing
        binding.port.isEnabled = !sharing

        if (sharing) {
            binding.pin.text = formatPin(state.pin)
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
        } else {
            binding.addressesLabel.visibility = View.GONE
            binding.addresses.visibility = View.GONE
            binding.clientStatus.text = getString(R.string.host_idle)
        }

        binding.message.visibility = if (state.message.isNullOrEmpty()) View.GONE else View.VISIBLE
        state.message?.let { binding.message.text = it }
    }

    private fun renderControlAvailability(enabled: Boolean) {
        binding.controlStatus.setText(
            if (enabled) R.string.host_control_enabled else R.string.host_control_disabled,
        )
        binding.buttonEnableControl.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun qualityLabel(quality: Quality): Int = when (quality) {
        Quality.LOW -> R.string.quality_low
        Quality.BALANCED -> R.string.quality_balanced
        Quality.HIGH -> R.string.quality_high
        Quality.NATIVE -> R.string.quality_native
    }

    /** "048213" reads much better as "048 213" when someone is reading it aloud. */
    private fun formatPin(value: String): String =
        if (value.length == Protocol.PIN_DIGITS) "${value.take(3)} ${value.drop(3)}" else value
}
