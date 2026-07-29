package com.siliconprime.tabletmirror.net

import com.siliconprime.tabletmirror.crypto.Hkdf
import com.siliconprime.tabletmirror.crypto.Identity
import com.siliconprime.tabletmirror.crypto.IdentitySigner
import com.siliconprime.tabletmirror.crypto.PairedPeer
import com.siliconprime.tabletmirror.crypto.TrustStore
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.KeyAgreement

class HandshakeException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason {
        VERSION_MISMATCH,

        /** The peer's identity is not pinned and pairing is not open. */
        NOT_PAIRED,

        /** A signature did not verify: wrong key, or the exchange was tampered with. */
        AUTH_FAILED,

        /** An operator rejected the pairing code, or it did not match. */
        PAIRING_DECLINED,

        MALFORMED,
        REJECTED,
    }
}

/** What the peer turned out to be, once it proved it. */
data class HandshakeResult(
    val peerName: String,
    val peerPublicKey: ByteArray,
    /** The compared code, retained for the audit log. */
    val sas: String,
    val newlyPaired: Boolean,
) {
    val fingerprint: String get() = Identity.fingerprint(peerPublicKey)
}

/**
 * Decides whether an unknown peer may pair, and gets a human to confirm the code.
 */
interface PairingAuthority {
    /**
     * Whether this device is currently willing to pair with an unknown peer.
     * Normal operation returns false, so an unsolicited pairing prompt can never
     * appear on a tablet that is just going about its business.
     */
    fun isPairingOpen(): Boolean

    /**
     * Shows [sas] and blocks until an operator confirms it matches the code on the
     * other tablet. Returning false aborts the connection.
     */
    fun confirmPairing(peerName: String, peerPublicKey: ByteArray, sas: String): Boolean
}

/**
 * Mutually authenticated key agreement between two paired tablets.
 *
 * Shape of the exchange (a SIGMA-style signed Diffie-Hellman, the same pattern
 * behind TLS 1.3 and Noise's IK):
 *
 *  1. Both sides generate a *throwaway* EC P-256 key pair and exchange the public
 *     halves along with their long-term identity public keys.
 *  2. The session keys come from ECDH over the throwaway keys, so they are
 *     forward secret: recording the traffic and later stealing both tablets does
 *     not decrypt what was captured, because the keys that protected it no longer
 *     exist anywhere.
 *  3. Each side signs a transcript hash covering the protocol version, both
 *     ephemeral keys, both identity keys and the pairing flag, using its identity
 *     key. Verifying that signature against a *pinned* identity is what
 *     authenticates the peer — there is no CA and no shared secret to steal.
 *  4. Only pinned identities may connect. An unpinned one is refused outright
 *     unless both operators have explicitly opened pairing.
 *
 * First-time pairing uses numeric comparison rather than a typed PIN. Both
 * tablets display a six-digit code derived from the completed exchange, and an
 * operator confirms they match. This is deliberate: a typed PIN is a secret that
 * an active attacker can capture and grind offline at leisure, whereas this code
 * is a *verification* of an exchange that has already happened. To defeat it, an
 * attacker sitting between the tablets would have to produce matching codes on
 * both screens, which means guessing right first time with probability one in a
 * million — and being caught the moment the codes differ.
 *
 * What this still does not defend against: a tablet that is itself compromised,
 * or an operator who confirms a code without actually comparing it against the
 * other screen. The pairing UI is worded to make the comparison the obvious act.
 */
object Handshake {

    private const val LABEL_HOST = "tabletmirror/v2 host-proof"
    private const val LABEL_VIEWER = "tabletmirror/v2 viewer-proof"
    private const val INFO_HOST_TO_VIEWER = "tabletmirror/v2 host-to-viewer"
    private const val INFO_VIEWER_TO_HOST = "tabletmirror/v2 viewer-to-host"
    private const val INFO_SAS = "tabletmirror/v2 sas"

    // -----------------------------------------------------------------------
    // Host
    // -----------------------------------------------------------------------

    fun asHost(
        channel: MessageChannel,
        identity: IdentitySigner,
        trustStore: TrustStore,
        authority: PairingAuthority,
        deviceName: String,
        now: () -> Long = System::currentTimeMillis,
    ): HandshakeResult {
        val hello = Hello.decode(expect(channel, MsgType.HELLO).payload)
        if (hello.magic != Protocol.MAGIC) {
            throw HandshakeException(
                HandshakeException.Reason.MALFORMED,
                "That is not a Tablet Mirror client.",
            )
        }
        if (hello.version != Protocol.VERSION) {
            refuse(channel, "Both tablets must run the same version of the app.")
            throw HandshakeException(
                HandshakeException.Reason.VERSION_MISMATCH,
                "Viewer speaks protocol ${hello.version}, this host speaks ${Protocol.VERSION}.",
            )
        }

        val pinned = trustStore.find(hello.identityKey)
        val pairing = pinned == null
        if (pairing && !authority.isPairingOpen()) {
            // Refuse before doing any key agreement: an unpinned device gets
            // nothing at all out of this exchange.
            refuse(channel, Protocol.REASON_NOT_PAIRED)
            throw HandshakeException(
                HandshakeException.Reason.NOT_PAIRED,
                "An unpaired tablet tried to connect and was refused.",
            )
        }

        val ephemeral = Identity.generateEphemeralKeyPair()
        val transcript = transcript(
            version = hello.version,
            viewerEphemeral = hello.ephemeralKey,
            viewerIdentity = hello.identityKey,
            hostEphemeral = ephemeral.public.encoded,
            hostIdentity = identity.publicKey,
            pairing = pairing,
        )

        channel.send(
            MsgType.CHALLENGE,
            Challenge(
                ephemeralKey = ephemeral.public.encoded,
                identityKey = identity.publicKey,
                deviceName = deviceName,
                pairing = pairing,
                signature = identity.sign(LABEL_HOST.toByteArray() + transcript),
            ).encode(),
        )

        val auth = Auth.decode(expect(channel, MsgType.AUTH).payload)
        if (!Identity.verify(
                hello.identityKey,
                LABEL_VIEWER.toByteArray() + transcript,
                auth.signature,
            )
        ) {
            refuse(channel, "Authentication failed.")
            throw HandshakeException(
                HandshakeException.Reason.AUTH_FAILED,
                "The viewer could not prove it holds the identity key it claimed.",
            )
        }

        val secret = sharedSecret(ephemeral.private, hello.ephemeralKey)
        val keys = deriveKeys(secret, transcript)
        channel.enableEncryption(
            SecureChannel(
                sendKey = keys.hostToViewer,
                recvKey = keys.viewerToHost,
                sendDirection = SecureChannel.DIR_HOST_TO_VIEWER,
                recvDirection = SecureChannel.DIR_VIEWER_TO_HOST,
            ),
        )

        if (pairing) {
            confirmPairing(channel, authority, hello.deviceName, hello.identityKey, keys.sas)
            trustStore.pin(PairedPeer(hello.identityKey, hello.deviceName, now()))
        }

        return HandshakeResult(
            peerName = pinned?.name ?: hello.deviceName,
            peerPublicKey = hello.identityKey,
            sas = keys.sas,
            newlyPaired = pairing,
        )
    }

    // -----------------------------------------------------------------------
    // Viewer
    // -----------------------------------------------------------------------

    fun asViewer(
        channel: MessageChannel,
        identity: IdentitySigner,
        trustStore: TrustStore,
        authority: PairingAuthority,
        deviceName: String,
        now: () -> Long = System::currentTimeMillis,
    ): HandshakeResult {
        val ephemeral = Identity.generateEphemeralKeyPair()
        channel.send(
            MsgType.HELLO,
            Hello(
                magic = Protocol.MAGIC,
                version = Protocol.VERSION,
                ephemeralKey = ephemeral.public.encoded,
                identityKey = identity.publicKey,
                deviceName = deviceName,
            ).encode(),
        )

        val challenge = Challenge.decode(expect(channel, MsgType.CHALLENGE).payload)
        val pinned = trustStore.find(challenge.identityKey)

        if (pinned == null && !challenge.pairing) {
            throw HandshakeException(
                HandshakeException.Reason.NOT_PAIRED,
                "This tablet is not paired with that one. Start pairing on both tablets.",
            )
        }
        if (pinned == null && !authority.isPairingOpen()) {
            // The host offered to pair but this side was not asked to. Refusing
            // stops an impostor from provoking a pairing prompt out of nowhere.
            throw HandshakeException(
                HandshakeException.Reason.NOT_PAIRED,
                "That tablet is offering to pair, but pairing was not started here.",
            )
        }
        if (pinned != null && challenge.pairing) {
            // The peer thinks it has never met us while we have it pinned. Either
            // the app was reinstalled there, or this is not the tablet we paired
            // with. Refuse rather than silently re-pairing.
            throw HandshakeException(
                HandshakeException.Reason.NOT_PAIRED,
                "That tablet no longer recognises this one. Unpair on both tablets, then pair again.",
            )
        }

        val transcript = transcript(
            version = Protocol.VERSION,
            viewerEphemeral = ephemeral.public.encoded,
            viewerIdentity = identity.publicKey,
            hostEphemeral = challenge.ephemeralKey,
            hostIdentity = challenge.identityKey,
            pairing = challenge.pairing,
        )

        if (!Identity.verify(
                challenge.identityKey,
                LABEL_HOST.toByteArray() + transcript,
                challenge.signature,
            )
        ) {
            throw HandshakeException(
                HandshakeException.Reason.AUTH_FAILED,
                "The host could not prove it holds the identity key it claimed.",
            )
        }

        channel.send(
            MsgType.AUTH,
            Auth(identity.sign(LABEL_VIEWER.toByteArray() + transcript)).encode(),
        )

        val secret = sharedSecret(ephemeral.private, challenge.ephemeralKey)
        val keys = deriveKeys(secret, transcript)
        channel.enableEncryption(
            SecureChannel(
                sendKey = keys.viewerToHost,
                recvKey = keys.hostToViewer,
                sendDirection = SecureChannel.DIR_VIEWER_TO_HOST,
                recvDirection = SecureChannel.DIR_HOST_TO_VIEWER,
            ),
        )

        if (challenge.pairing) {
            confirmPairing(
                channel,
                authority,
                challenge.deviceName,
                challenge.identityKey,
                keys.sas,
            )
            trustStore.pin(PairedPeer(challenge.identityKey, challenge.deviceName, now()))
        }

        return HandshakeResult(
            peerName = pinned?.name ?: challenge.deviceName,
            peerPublicKey = challenge.identityKey,
            sas = keys.sas,
            newlyPaired = challenge.pairing,
        )
    }

    // -----------------------------------------------------------------------
    // Shared pieces
    // -----------------------------------------------------------------------

    /**
     * Both operators must accept, and each side learns the other's answer over the
     * already-encrypted channel. Sending our own answer first would let a peer
     * that has been told "no" keep the connection; requiring both means either
     * side can veto.
     */
    private fun confirmPairing(
        channel: MessageChannel,
        authority: PairingAuthority,
        peerName: String,
        peerKey: ByteArray,
        sas: String,
    ) {
        val accepted = authority.confirmPairing(peerName, peerKey, sas)
        channel.send(MsgType.PAIR_RESULT, PairResult(accepted).encode())
        val peerAccepted = runCatching {
            PairResult.decode(expect(channel, MsgType.PAIR_RESULT).payload).accepted
        }.getOrDefault(false)

        if (!accepted || !peerAccepted) {
            throw HandshakeException(
                HandshakeException.Reason.PAIRING_DECLINED,
                if (!accepted) {
                    "Pairing was cancelled on this tablet."
                } else {
                    "Pairing was cancelled on the other tablet."
                },
            )
        }
    }

    /**
     * Everything that must be identical on both sides, hashed. Any difference —
     * a substituted key, a flipped pairing flag, a downgraded version — changes
     * the hash, which breaks both signatures and changes the comparison code.
     */
    internal fun transcript(
        version: Int,
        viewerEphemeral: ByteArray,
        viewerIdentity: ByteArray,
        hostEphemeral: ByteArray,
        hostIdentity: ByteArray,
        pairing: Boolean,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("tabletmirror/v2".toByteArray())
        digest.update(intBytes(version))
        for (field in listOf(viewerEphemeral, viewerIdentity, hostEphemeral, hostIdentity)) {
            // Length-prefix every field so no two different field splits can ever
            // hash to the same transcript.
            digest.update(intBytes(field.size))
            digest.update(field)
        }
        digest.update(if (pairing) 1 else 0)
        return digest.digest()
    }

    internal class SessionKeys(
        val hostToViewer: ByteArray,
        val viewerToHost: ByteArray,
        val sas: String,
    )

    internal fun deriveKeys(sharedSecret: ByteArray, transcript: ByteArray): SessionKeys {
        val prk = Hkdf.extract(salt = transcript, ikm = sharedSecret)
        return SessionKeys(
            hostToViewer = Hkdf.expand(
                prk,
                INFO_HOST_TO_VIEWER.toByteArray(),
                SecureChannel.KEY_BYTES,
            ),
            viewerToHost = Hkdf.expand(
                prk,
                INFO_VIEWER_TO_HOST.toByteArray(),
                SecureChannel.KEY_BYTES,
            ),
            sas = sasFrom(Hkdf.expand(prk, INFO_SAS.toByteArray(), 8)),
        )
    }

    /**
     * Six decimal digits from the derived bytes. Reducing 64 bits modulo a million
     * leaves a bias far too small to matter for a code that is only ever compared
     * once, by eye.
     */
    internal fun sasFrom(bytes: ByteArray): String {
        var value = 0L
        for (i in 0 until minOf(8, bytes.size)) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        val modulus = 1_000_000L
        return ((value ushr 1) % modulus).toString().padStart(Protocol.SAS_DIGITS, '0')
    }

    private fun sharedSecret(privateKey: java.security.PrivateKey, peerEphemeral: ByteArray): ByteArray {
        val peerKey = try {
            // Decoding rejects malformed encodings and points off the curve, which
            // is what stops an invalid-curve attack on the agreement.
            Identity.decodePublicKey(peerEphemeral)
        } catch (e: Exception) {
            throw HandshakeException(
                HandshakeException.Reason.MALFORMED,
                "The other tablet sent an unusable key.",
            )
        }
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peerKey, true)
            generateSecret()
        }
    }

    private fun refuse(channel: MessageChannel, reason: String) {
        runCatching { channel.send(MsgType.BYE, buildPayload { it.writeUTF(reason) }) }
    }

    private fun expect(channel: MessageChannel, type: MsgType): Message {
        val message = channel.receive()
            ?: throw HandshakeException(
                HandshakeException.Reason.REJECTED,
                "The connection closed during setup.",
            )
        if (message.type == MsgType.BYE) {
            val reason = runCatching { readPayload(message.payload) { it.readUTF() } }
                .getOrDefault("")
            throw if (reason == Protocol.REASON_NOT_PAIRED) {
                HandshakeException(
                    HandshakeException.Reason.NOT_PAIRED,
                    "That tablet has not been paired with this one. " +
                        "Start pairing on both tablets, then try again.",
                )
            } else {
                HandshakeException(
                    HandshakeException.Reason.REJECTED,
                    reason.ifEmpty { "The other tablet refused the connection." },
                )
            }
        }
        if (message.type != type) {
            throw HandshakeException(
                HandshakeException.Reason.MALFORMED,
                "Expected ${type.name} but received ${message.type.name}.",
            )
        }
        return message
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
