package com.siliconprime.tabletmirror.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.databinding.ActivityMainBinding
import com.siliconprime.tabletmirror.host.MirrorAccessibilityService

/** Role picker: this tablet is either the one being shared or the one driving. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonShare.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        binding.buttonControl.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        binding.buttonAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = MirrorAccessibilityService.isEnabledInSettings(this)
        binding.controlStatus.setText(
            if (enabled) R.string.main_control_enabled else R.string.main_control_disabled,
        )
        binding.buttonAccessibilitySettings.setText(
            if (enabled) R.string.action_open_accessibility_settings_change
            else R.string.action_open_accessibility_settings,
        )
    }

    private fun openAccessibilitySettings() {
        // There is no API to enable an accessibility service programmatically, by
        // design — the user has to do it, so send them to the right screen.
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}
