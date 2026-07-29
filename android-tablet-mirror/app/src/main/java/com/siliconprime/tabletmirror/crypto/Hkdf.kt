package com.siliconprime.tabletmirror.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869).
 *
 * Neither the JDK nor Android exposes HKDF as a public API, so it is implemented
 * here over `Hmac`. The construction is small and exactly specified, and the unit
 * tests check it against the RFC's own test vectors rather than trusting it by
 * inspection.
 *
 * Used to turn a raw ECDH shared secret into independent, purpose-bound keys: one
 * per direction, plus the short authentication string. Deriving each with a
 * distinct `info` label means no two uses can ever collide on the same bytes.
 */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    /**
     * HKDF-Extract: condenses possibly non-uniform key material into a
     * fixed-length pseudorandom key.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        // RFC 5869: an absent salt is treated as HashLen zero bytes. An all-zero
        // HMAC key is legal here, but SecretKeySpec rejects an empty one.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        mac.init(SecretKeySpec(effectiveSalt, ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** HKDF-Expand: stretches a pseudorandom key into [length] bytes bound to [info]. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        require(length <= 255 * HASH_LEN) { "HKDF cannot produce $length bytes" }

        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(prk, ALGORITHM))

        val output = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val take = minOf(block.size, length - offset)
            block.copyInto(output, offset, 0, take)
            offset += take
            counter++
        }
        return output
    }

    /** Extract-then-expand in one step. */
    fun derive(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(salt, ikm), info.toByteArray(Charsets.UTF_8), length)
}
