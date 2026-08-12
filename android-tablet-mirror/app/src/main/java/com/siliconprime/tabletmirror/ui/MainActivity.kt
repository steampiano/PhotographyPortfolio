package com.siliconprime.tabletmirror.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.databinding.ActivityMainBinding
import com.siliconprime.tabletmirror.host.MirrorAccessibilityService
import com.siliconprime.tabletmirror.viewer.ViewerPrefs

/** Role picker: this tablet is either the one being shared or the one driving. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ViewerPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBars.pad(binding.scroll, top = false)

        prefs = ViewerPrefs(this)

        binding.buttonShare.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }
        binding.buttonControl.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }
        binding.buttonChooseOther.setOnClickListener {
            startActivity(ConnectActivity.pickIntent(this))
        }
        binding.buttonAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        renderRememberedHost()
        val enabled = MirrorAccessibilityService.isEnabledInSettings(this)
        binding.controlStatus.setText(
            if (enabled) R.string.main_control_enabled else R.string.main_control_disabled,
        )
        binding.buttonAccessibilitySettings.setText(
            if (enabled) R.string.action_open_accessibility_settings_change
            else R.string.action_open_accessibility_settings,
        )
    }

    /**
     * Only worth offering once there is something to choose between. Naming the
     * remembered tablet also makes it obvious *why* the button above skips the
     * chooser, which otherwise looks like the app ignoring the tap.
     */
    private fun renderRememberedHost() {
        val remembered = prefs.recentHosts.firstOrNull()
        val show = remembered != null
        binding.buttonChooseOther.visibility = if (show) View.VISIBLE else View.GONE
        binding.chooseOtherHint.visibility = if (show) View.VISIBLE else View.GONE
        if (remembered != null) {
            binding.chooseOtherHint.text = getString(
                R.string.main_choose_other_hint,
                remembered.name.ifEmpty { remembered.address },
            )
        }
    }

    private fun openAccessibilitySettings() {
        // There is no API to enable an accessibility service programmatically, by
        // design — the user has to do it, so send them to the right screen.
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}
