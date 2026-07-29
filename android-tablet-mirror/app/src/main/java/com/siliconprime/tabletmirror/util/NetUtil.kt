package com.siliconprime.tabletmirror.util

import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /**
     * IPv4 addresses a viewer on the same network could dial, best candidate
     * first. Shown in the host UI so the pair can be connected by hand when mDNS
     * discovery is blocked, which is common on guest and enterprise Wi-Fi.
     */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { iface.name to it.hostAddress.orEmpty() }
            }
            .filter { it.second.isNotEmpty() }
            // Wi-Fi first: a tablet may also expose usb0/rndis or a VPN tunnel.
            .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else 1 }
            .map { it.second }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** A human-friendly name for this tablet, used in the pairing UI. */
    fun deviceLabel(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            model.isEmpty() && manufacturer.isEmpty() -> "Android tablet"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isEmpty() -> model
            else -> "$manufacturer $model"
        }
    }
}
