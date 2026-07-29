package com.siliconprime.tabletmirror.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature

/**
 * This device's identity key, held in the Android Keystore.
 *
 * The private key is generated inside the Keystore and marked non-exportable, so
 * it is only ever used by handle. On hardware with a TEE or StrongBox it never
 * exists in the application processor's memory at all — which means that even a
 * compromised or rooted device cannot copy this tablet's identity onto another
 * one and impersonate it to its paired peer.
 *
 * No user authentication is required to use the key: the tablets need to
 * reconnect unattended after a reboot or a Wi-Fi blip, and a POS screen behind a
 * locked device is not the threat being defended against here.
 */
class KeystoreIdentity private constructor(
    private val privateKey: PrivateKey,
    override val publicKey: ByteArray,
    /** True when the key is held in a TEE or StrongBox rather than software. */
    val hardwareBacked: Boolean,
) : IdentitySigner {

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(Identity.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        private const val TAG = "KeystoreIdentity"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "tabletmirror.identity.v1"

        /** Loads this device's identity, creating it on first use. */
        fun loadOrCreate(): KeystoreIdentity {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                generate(preferStrongBox = true)
            }
            return open(store)
                ?: run {
                    // A key that cannot be opened is unusable; replace it rather
                    // than leaving the app permanently unable to authenticate.
                    Log.w(TAG, "identity key unusable, regenerating")
                    store.deleteEntry(ALIAS)
                    generate(preferStrongBox = true)
                    val reopened = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                    requireNotNull(open(reopened)) { "could not create an identity key" }
                }
        }

        private fun open(store: KeyStore): KeystoreIdentity? = runCatching {
            val privateKey = store.getKey(ALIAS, null) as? PrivateKey ?: return null
            val certificate = store.getCertificate(ALIAS) ?: return null
            val encoded = certificate.publicKey.encoded ?: return null
            // Prove the key actually works before handing it out: a device that
            // has lost its TEE state can otherwise fail later, mid-handshake.
            Signature.getInstance(Identity.SIGNATURE_ALGORITHM).apply {
                initSign(privateKey)
                update(byteArrayOf(0))
                sign()
            }
            KeystoreIdentity(privateKey, encoded, isInsideSecureHardware(store))
        }.getOrNull()

        private fun generate(preferStrongBox: Boolean) {
            try {
                createKey(strongBox = preferStrongBox)
            } catch (e: Exception) {
                if (!preferStrongBox) throw e
                // StrongBox is absent on most tablets; fall back to the TEE.
                Log.i(TAG, "StrongBox unavailable, using the default keystore")
                createKey(strongBox = false)
            }
        }

        private fun createKey(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameter(java.security.spec.ECGenParameterSpec(Identity.CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply {
                    if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(spec)
            }.generateKeyPair()
        }

        @Suppress("DEPRECATION")
        private fun isInsideSecureHardware(store: KeyStore): Boolean = runCatching {
            val privateKey = store.getKey(ALIAS, null) as PrivateKey
            val factory = java.security.KeyFactory.getInstance(privateKey.algorithm, KEYSTORE)
            val info = factory.getKeySpec(privateKey, android.security.keystore.KeyInfo::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                info.securityLevel != android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                info.isInsideSecureHardware
            }
        }.getOrDefault(false)
    }
}
