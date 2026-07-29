package com.siliconprime.tabletmirror.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** A host found on the local network. */
data class DiscoveredHost(
    val serviceName: String,
    val address: String,
    val port: Int,
)

/**
 * Publishes this tablet as a mirroring host over mDNS.
 *
 * Discovery is a convenience only. Plenty of networks block multicast, so the
 * host UI always shows its IP address as a fallback and the viewer always
 * accepts one typed in by hand.
 */
class HostAdvertiser(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int, serviceName: String) {
        if (nsd == null || listener != null) return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = Protocol.SERVICE_TYPE
            this.port = port
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registration
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                Log.w(TAG, "could not advertise: ${it.message}")
                listener = null
            }
    }

    fun unregister() {
        val current = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(current) }
    }

    private companion object {
        const val TAG = "HostAdvertiser"
    }
}

/** Browses for hosts. Resolution is serialised because NsdManager dislikes overlap. */
class HostBrowser(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    fun start(onFound: (DiscoveredHost) -> Unit, onError: (String) -> Unit) {
        if (nsd == null) {
            onError("This device does not support network discovery.")
            return
        }
        if (discoveryListener != null) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                enqueueResolve(info, onFound)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onError("Discovery unavailable on this network (code $errorCode).")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
            onError(it.message ?: "Could not start discovery.")
        }
    }

    fun stop() {
        val current = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd?.stopServiceDiscovery(current) }
        synchronized(lock) {
            pendingResolves.clear()
            resolving = false
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo, onFound: (DiscoveredHost) -> Unit) {
        synchronized(lock) {
            pendingResolves.addLast(info)
            if (resolving) return
        }
        drainResolves(onFound)
    }

    private fun drainResolves(onFound: (DiscoveredHost) -> Unit) {
        val next = synchronized(lock) {
            if (pendingResolves.isEmpty()) {
                resolving = false
                return
            }
            resolving = true
            pendingResolves.removeFirst()
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val address = resolved.host?.hostAddress
                if (!address.isNullOrEmpty()) {
                    onFound(DiscoveredHost(resolved.serviceName, address, resolved.port))
                }
                drainResolves(onFound)
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                drainResolves(onFound)
            }
        }

        // resolveService is deprecated in favour of registerServiceInfoCallback on
        // API 34+, but remains the only option down to our minSdk of 26.
        @Suppress("DEPRECATION")
        runCatching { nsd?.resolveService(next, resolveListener) }
            .onFailure { drainResolves(onFound) }
    }

    private companion object {
        const val TAG = "HostBrowser"
    }
}
