package com.siliconprime.tabletmirror.net

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class HandshakeException(val reason: Reason, message: String) : IOException(message) {
    enum class Reason { VERSION_MISMATCH, BAD_PIN, REJECTED, MALFORMED }
}

/** What the far end told us about itself once the PIN checked out. */
data class PeerInfo(val deviceName: String, val secure: SecureChannel)

/**
 * PIN-authenticated key agreement.
 *
 * Both peers derive the session key from the shared PIN plus fresh nonces from
 * each side, then prove knowledge of it with domain-separated HMACs before any
 * screen content moves. Fresh nonces mean a recorded session cannot be replayed
 * against a later one even when the PIN is reused.
 *
 * Threat model, stated plainly: this stops a passive eavesdropper and a casual
 * intruder on the same Wi-Fi, and it prevents an unauthenticated device from
 * connecting at all. It does *not* defeat an attacker who can impersonate the
 * host at the TCP level while a viewer connects — such an attacker collects the
 * viewer's HMAC and can grind a 6-digit PIN offline. The mitigation is that the
 * host mints a new random PIN on every start, so a cracked PIN is worthless
 * afterwards; do not turn the PIN into a memorable constant.
 */
object Handshake {
    private const val PBKDF2_ITERATIONS = 120_000
    private const val HMAC = "HmacSHA256"

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    /** A fresh zero-padded PIN, e.g. "048213". */
    fun randomPin(): String {
        val bound = 1_000_000
        return random.nextInt(bound).toString().padStart(Protocol.PIN_DIGITS, '0')
    }

    fun deriveKey(
        pin: String,
        salt: ByteArray,
        viewerNonce: ByteArray,
        hostNonce: ByteArray,
    ): ByteArray {
        // Both nonces join the PBKDF2 salt so the derived key is session-unique.
        val fullSalt = salt + viewerNonce + hostNonce
        val spec = PBEKeySpec(
            pin.toCharArray(),
            fullSalt,
            PBKDF2_ITERATIONS,
            SecureChannel.KEY_BYTES * 8,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun proof(key: ByteArray, label: String, first: ByteArray, second: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        mac.update(label.toByteArray(Charsets.UTF_8))
        mac.update(first)
        mac.update(second)
        return mac.doFinal()
    }

    // -----------------------------------------------------------------------
    // Viewer side
    // -----------------------------------------------------------------------

    fun asViewer(channel: MessageChannel, pin: String, deviceName: String): PeerInfo {
        val viewerNonce = randomBytes(Protocol.NONCE_BYTES)
        channel.send(
            MsgType.HELLO,
            buildPayload { out ->
                out.writeInt(Protocol.MAGIC)
                out.writeInt(Protocol.VERSION)
                out.write(viewerNonce)
                out.writeUTF(deviceName)
            },
        )

        val challenge = expect(channel, MsgType.CHALLENGE)
        val (salt, hostNonce, hostName) = readPayload(challenge.payload) { inp ->
            val salt = ByteArray(Protocol.SALT_BYTES).also(inp::readFully)
            val nonce = ByteArray(Protocol.NONCE_BYTES).also(inp::readFully)
            Triple(salt, nonce, inp.readUTF())
        }

        val key = deriveKey(pin, salt, viewerNonce, hostNonce)
        channel.send(MsgType.AUTH_VIEWER, proof(key, LABEL_VIEWER, viewerNonce, hostNonce))

        val authHost = expect(channel, MsgType.AUTH_HOST)
        val expected = proof(key, LABEL_HOST, hostNonce, viewerNonce)
        if (!MessageDigest.isEqual(expected, authHost.payload)) {
            throw HandshakeException(
                HandshakeException.Reason.BAD_PIN,
                "Host could not prove it knows the PIN.",
            )
        }

        val secure = SecureChannel(
            key,
            sendDirection = SecureChannel.DIR_VIEWER_TO_HOST,
            recvDirection = SecureChannel.DIR_HOST_TO_VIEWER,
        )
        channel.enableEncryption(secure)
        return PeerInfo(hostName, secure)
    }

    // -----------------------------------------------------------------------
    // Host side
    // -----------------------------------------------------------------------

    fun asHost(channel: MessageChannel, pin: String, deviceName: String): PeerInfo {
        val hello = expect(channel, MsgType.HELLO)
        val (viewerNonce, viewerName) = readPayload(hello.payload) { inp ->
            val magic = inp.readInt()
            val version = inp.readInt()
            if (magic != Protocol.MAGIC) {
                throw HandshakeException(
                    HandshakeException.Reason.MALFORMED,
                    "Not a Tablet Mirror client.",
                )
            }
            if (version != Protocol.VERSION) {
                throw HandshakeException(
                    HandshakeException.Reason.VERSION_MISMATCH,
                    "Viewer speaks protocol $version, host speaks ${Protocol.VERSION}. " +
                        "Update both tablets to the same build.",
                )
            }
            val nonce = ByteArray(Protocol.NONCE_BYTES).also(inp::readFully)
            nonce to inp.readUTF()
        }

        val salt = randomBytes(Protocol.SALT_BYTES)
        val hostNonce = randomBytes(Protocol.NONCE_BYTES)
        channel.send(
            MsgType.CHALLENGE,
            buildPayload { out ->
                out.write(salt)
                out.write(hostNonce)
                out.writeUTF(deviceName)
            },
        )

        val key = deriveKey(pin, salt, viewerNonce, hostNonce)
        val authViewer = expect(channel, MsgType.AUTH_VIEWER)
        val expected = proof(key, LABEL_VIEWER, viewerNonce, hostNonce)
        if (!MessageDigest.isEqual(expected, authViewer.payload)) {
            // Tell the viewer why before hanging up, so it can say "wrong PIN"
            // instead of reporting a bare connection reset.
            runCatching { channel.send(MsgType.BYE, buildPayload { it.writeUTF(REASON_BAD_PIN) }) }
            throw HandshakeException(
                HandshakeException.Reason.BAD_PIN,
                "Viewer sent the wrong PIN.",
            )
        }

        channel.send(MsgType.AUTH_HOST, proof(key, LABEL_HOST, hostNonce, viewerNonce))
        val secure = SecureChannel(
            key,
            sendDirection = SecureChannel.DIR_HOST_TO_VIEWER,
            recvDirection = SecureChannel.DIR_VIEWER_TO_HOST,
        )
        channel.enableEncryption(secure)
        return PeerInfo(viewerName, secure)
    }

    private fun expect(channel: MessageChannel, type: MsgType): Message {
        val msg = channel.receive()
            ?: throw HandshakeException(
                HandshakeException.Reason.REJECTED,
                "Connection closed during handshake.",
            )
        if (msg.type == MsgType.BYE) {
            val reason = runCatching { readPayload(msg.payload) { it.readUTF() } }.getOrDefault("")
            throw if (reason == REASON_BAD_PIN) {
                HandshakeException(HandshakeException.Reason.BAD_PIN, "Host rejected the PIN.")
            } else {
                HandshakeException(
                    HandshakeException.Reason.REJECTED,
                    reason.ifEmpty { "Host refused the connection." },
                )
            }
        }
        if (msg.type != type) {
            throw HandshakeException(
                HandshakeException.Reason.MALFORMED,
                "Expected ${type.name} but received ${msg.type.name}.",
            )
        }
        return msg
    }

    private const val LABEL_VIEWER = "tabletmirror-viewer-proof"
    private const val LABEL_HOST = "tabletmirror-host-proof"
    private const val REASON_BAD_PIN = "bad-pin"
}
