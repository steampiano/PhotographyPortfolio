package com.siliconprime.tabletmirror.net

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM framing for one direction pair of an established session.
 *
 * Each direction gets its own implicit 64-bit sequence counter, so IVs never
 * repeat under a given key and neither side has to transmit them. Because the
 * receiver derives the IV from its own counter, a replayed, reordered or dropped
 * message fails authentication instead of being silently accepted — the channel
 * is strictly ordered and any tampering is fatal.
 *
 * The message type is bound in as additional authenticated data so an attacker
 * cannot relabel a sealed payload (e.g. replay a video frame as a touch event).
 */
class SecureChannel(
    key: ByteArray,
    private val sendDirection: Byte,
    private val recvDirection: Byte,
) {
    init {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes, was ${key.size}" }
        require(sendDirection != recvDirection) { "directions must differ" }
    }

    private val secret = SecretKeySpec(key, "AES")
    private var sendSeq = 0L
    private var recvSeq = 0L

    fun seal(type: Int, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret, spec(sendDirection, sendSeq))
        cipher.updateAAD(aad(type, sendSeq))
        sendSeq++
        return cipher.doFinal(plaintext)
    }

    @Throws(GeneralSecurityException::class)
    fun open(type: Int, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret, spec(recvDirection, recvSeq))
        cipher.updateAAD(aad(type, recvSeq))
        // Advance regardless of outcome: a failed open aborts the connection, and
        // reusing the counter after a failure would be worse than desynchronising.
        recvSeq++
        return cipher.doFinal(sealed)
    }

    private fun spec(direction: Byte, seq: Long): GCMParameterSpec =
        GCMParameterSpec(TAG_BITS, iv(direction, seq))

    private fun iv(direction: Byte, seq: Long): ByteArray {
        val iv = ByteArray(IV_BYTES)
        iv[0] = direction
        for (i in 0 until 8) {
            iv[IV_BYTES - 1 - i] = (seq ushr (8 * i)).toByte()
        }
        return iv
    }

    private fun aad(type: Int, seq: Long): ByteArray {
        val aad = ByteArray(9)
        aad[0] = type.toByte()
        for (i in 0 until 8) {
            aad[8 - i] = (seq ushr (8 * i)).toByte()
        }
        return aad
    }

    companion object {
        const val KEY_BYTES = 32
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        private const val IV_BYTES = 12
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Direction tags keep the two halves of a session in separate IV spaces. */
        const val DIR_HOST_TO_VIEWER: Byte = 1
        const val DIR_VIEWER_TO_HOST: Byte = 2
    }
}
