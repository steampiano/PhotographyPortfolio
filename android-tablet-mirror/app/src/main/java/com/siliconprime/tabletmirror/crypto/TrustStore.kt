package com.siliconprime.tabletmirror.crypto

/** A peer this device has paired with and now trusts. */
data class PairedPeer(
    val publicKey: ByteArray,
    val name: String,
    val pairedAtMillis: Long,
) {
    val fingerprint: String get() = Identity.fingerprint(publicKey)

    override fun equals(other: Any?): Boolean =
        other is PairedPeer && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = publicKey.contentHashCode()
}

/**
 * The set of peer identities this device will talk to.
 *
 * Pinning is the whole basis of authentication after pairing: an identity that is
 * not in here cannot connect, no matter what it claims about itself.
 */
interface TrustStore {
    fun find(publicKey: ByteArray): PairedPeer?
    fun all(): List<PairedPeer>
    fun pin(peer: PairedPeer)
    fun forget(publicKey: ByteArray)
}

/** Non-persistent store, used by the unit tests. */
class InMemoryTrustStore(initial: List<PairedPeer> = emptyList()) : TrustStore {
    private val peers = initial.toMutableList()

    override fun find(publicKey: ByteArray): PairedPeer? =
        peers.firstOrNull { Identity.sameKey(it.publicKey, publicKey) }

    override fun all(): List<PairedPeer> = peers.toList()

    override fun pin(peer: PairedPeer) {
        forget(peer.publicKey)
        peers.add(peer)
    }

    override fun forget(publicKey: ByteArray) {
        peers.removeAll { Identity.sameKey(it.publicKey, publicKey) }
    }
}
