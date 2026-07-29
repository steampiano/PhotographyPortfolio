package com.siliconprime.tabletmirror.crypto

/**
 * Process-wide holder for this device's identity key.
 *
 * Loading it touches the Android Keystore, and creating it on first run generates
 * a key in secure hardware. Both are cheap but not free, and both would otherwise
 * happen every time the viewer screen opens or sharing starts. There is exactly
 * one identity per device, so caching it is also the clearer model.
 */
object DeviceIdentity {

    @Volatile
    private var cached: KeystoreIdentity? = null

    fun get(): KeystoreIdentity =
        cached ?: synchronized(this) {
            cached ?: KeystoreIdentity.loadOrCreate().also { cached = it }
        }
}
