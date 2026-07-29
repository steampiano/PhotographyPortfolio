package com.siliconprime.tabletmirror.host

import android.content.Context

/**
 * Host-side policy, persisted.
 *
 * [controlAllowed] is the switch that decides whether this tablet accepts remote
 * input at all. It is enforced on the host for every incoming message rather than
 * being advertised to the viewer as a request, so turning it off is a real
 * restriction and not a courtesy the viewer could ignore.
 *
 * It defaults to **off**: a freshly installed mirror shows the screen and nothing
 * more, and someone has to make a deliberate decision before a second device can
 * touch a till.
 */
class HostSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var controlAllowed: Boolean
        get() = prefs.getBoolean(KEY_CONTROL_ALLOWED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTROL_ALLOWED, value).apply()

    private companion object {
        const val FILE = "host_settings"
        const val KEY_CONTROL_ALLOWED = "control_allowed"
    }
}
