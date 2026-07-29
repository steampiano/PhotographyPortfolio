package com.siliconprime.tabletmirror.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * A device's long-term identity: an EC P-256 signing key that never leaves the
 * device, plus its public half, which peers pin at pairing time.
 *
 * Kept behind an interface so the handshake can be unit tested with an in-memory
 * key while the real app uses a hardware-backed key in the Android Keystore.
 */
interface IdentitySigner {
    /** X.509 SubjectPublicKeyInfo encoding — what gets pinned and compared. */
    val publicKey: ByteArray

    fun sign(data: ByteArray): ByteArray
}

object Identity {
    const val CURVE = "secp256r1"
    const val KEY_ALGORITHM = "EC"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Verifies [signature] over [data] against a peer's encoded public key. */
    fun verify(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val key = decodePublicKey(publicKeyBytes)
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

    fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))

    /**
     * A stable, human-comparable fingerprint of a public key, e.g.
     * `A1B2 C3D4 E5F6 0708`. Shown in the paired-device list so an operator can
     * confirm which device a pinned entry refers to.
     */
    fun fingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.take(8)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }

    /** True when both encodings describe the same key. Constant time. */
    fun sameKey(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()
}

/**
 * A software identity key held in memory.
 *
 * Used by the unit tests. The app uses [com.siliconprime.tabletmirror.crypto.KeystoreIdentity]
 * instead, so that the private key is non-exportable and hardware-backed.
 */
class SoftwareIdentity(private val keyPair: KeyPair = Identity.generateEphemeralKeyPair()) :
    IdentitySigner {

    override val publicKey: ByteArray = keyPair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(Identity.SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(data)
            sign()
        }
}
