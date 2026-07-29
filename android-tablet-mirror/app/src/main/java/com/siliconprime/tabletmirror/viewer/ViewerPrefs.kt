package com.siliconprime.tabletmirror.viewer

import android.content.Context

/**
 * Remembers which tablet this one drives, so that opening the app is the only
 * action needed. Nothing secret lives here — the address is a convenience, and
 * trust still comes from the pinned identity in the trust store.
 */
class ViewerPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastEndpoint: Endpoint?
        get() {
            val address = prefs.getString(KEY_ADDRESS, null) ?: return null
            val port = prefs.getInt(KEY_PORT, 0)
            return if (address.isEmpty() || port !in 1..65535) null else Endpoint(address, port)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_ADDRESS)
                    remove(KEY_PORT)
                } else {
                    putString(KEY_ADDRESS, value.address)
                    putInt(KEY_PORT, value.port)
                }
            }.apply()
        }

    private companion object {
        const val FILE = "viewer_prefs"
        const val KEY_ADDRESS = "last_address"
        const val KEY_PORT = "last_port"
    }
}
