package com.siliconprime.tabletmirror.viewer

import android.content.Context

/**
 * Remembers which tablets this one drives, so that opening the app is the only
 * action needed. Nothing secret lives here — the addresses are a convenience, and
 * trust still comes from the pinned identity in the trust store.
 *
 * The fingerprints kept alongside are not used to decide anything; they are how the
 * app can name the right pin when a host has to be forgotten and paired again.
 */
class ViewerPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Most recent first. */
    val recentHosts: List<RecentHost>
        get() = RecentHosts.decode(prefs.getString(KEY_RECENT, null))

    /**
     * Where to reconnect on launch. Falls back to the standalone keys written by
     * versions before the list existed, so an already-paired tablet does not lose
     * its host on update.
     */
    val lastEndpoint: Endpoint?
        get() = recentHosts.firstOrNull()?.endpoint ?: legacyEndpoint()

    /** Records a tablet we actually reached and authenticated. */
    fun remember(host: RecentHost) {
        prefs.edit()
            .putString(KEY_RECENT, RecentHosts.encode(RecentHosts.merge(recentHosts, host)))
            .putString(KEY_ADDRESS, host.address)
            .putInt(KEY_PORT, host.port)
            .apply()
    }

    /** Drops a tablet from the list, used when it has unpaired this one. */
    fun forget(endpoint: Endpoint) {
        val remaining = recentHosts.filterNot { it.endpoint == endpoint }
        prefs.edit().apply {
            putString(KEY_RECENT, RecentHosts.encode(remaining))
            val head = remaining.firstOrNull()
            if (head == null) {
                remove(KEY_ADDRESS)
                remove(KEY_PORT)
            } else {
                putString(KEY_ADDRESS, head.address)
                putInt(KEY_PORT, head.port)
            }
        }.apply()
    }

    private fun legacyEndpoint(): Endpoint? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 0)
        return if (address.isEmpty() || port !in 1..65535) null else Endpoint(address, port)
    }

    private companion object {
        const val FILE = "viewer_prefs"
        const val KEY_ADDRESS = "last_address"
        const val KEY_PORT = "last_port"
        const val KEY_RECENT = "recent_hosts"
    }
}
