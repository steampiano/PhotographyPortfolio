package com.siliconprime.tabletmirror.crypto

import android.content.Context
import android.util.Base64

/**
 * Pinned peers, persisted in app-private storage.
 *
 * These are public keys, so they need integrity rather than secrecy — nothing is
 * gained by encrypting them, and the app sandbox already prevents other apps from
 * editing them. What matters is that only a deliberate unpair removes an entry,
 * because forgetting a pin is what would let an impostor pair in its place.
 */
class PreferencesTrustStore(context: Context) : TrustStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun find(publicKey: ByteArray): PairedPeer? =
        all().firstOrNull { Identity.sameKey(it.publicKey, publicKey) }

    override fun all(): List<PairedPeer> = prefs.all.entries.mapNotNull { (key, value) ->
        val encoded = value as? String ?: return@mapNotNull null
        runCatching {
            val parts = encoded.split(SEPARATOR, limit = 2)
            PairedPeer(
                publicKey = Base64.decode(key, Base64.NO_WRAP),
                name = parts.getOrElse(1) { "" },
                pairedAtMillis = parts[0].toLong(),
            )
        }.getOrNull()
    }.sortedBy(PairedPeer::pairedAtMillis)

    override fun pin(peer: PairedPeer) {
        prefs.edit()
            .putString(
                Base64.encodeToString(peer.publicKey, Base64.NO_WRAP),
                "${peer.pairedAtMillis}$SEPARATOR${peer.name}",
            )
            .apply()
    }

    override fun forget(publicKey: ByteArray) {
        prefs.edit().remove(Base64.encodeToString(publicKey, Base64.NO_WRAP)).apply()
    }

    private companion object {
        const val FILE = "paired_devices"
        /** Written as an escape so the delimiter cannot be mangled by an editor. */
        const val SEPARATOR = "\t"
    }
}
