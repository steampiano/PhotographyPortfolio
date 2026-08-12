package com.siliconprime.tabletmirror.viewer

/**
 * A tablet this one has successfully driven before.
 *
 * The fingerprint is carried alongside the address because it is the only stable
 * name a host has: addresses move with the DHCP lease, and the device label can be
 * renamed in Settings. It is also what lets the viewer forget exactly the right pin
 * when a host has unpaired it, instead of clearing every pin on the tablet.
 */
data class RecentHost(
    val address: String,
    val port: Int,
    val name: String,
    val fingerprint: String,
) {
    val endpoint: Endpoint get() = Endpoint(address, port)
}

/**
 * The short list of tablets offered on the connect screen, most recent first.
 *
 * Deliberately not a growing history. In a shop there are one or two tills, so a
 * long list would be all noise; what matters is that the one you used last is the
 * one already filled in.
 *
 * Pure logic with no Android types, so the ordering and the round trip through
 * storage are unit tested.
 */
object RecentHosts {

    const val MAX = 5

    private const val RECORD = "\n"
    private const val FIELD = "\t"

    /** Moves [host] to the front, replacing any earlier entry for the same place. */
    fun merge(existing: List<RecentHost>, host: RecentHost): List<RecentHost> {
        val kept = existing.filterNot {
            it.endpoint == host.endpoint || it.fingerprint == host.fingerprint
        }
        return (listOf(host) + kept).take(MAX)
    }

    fun forEndpoint(hosts: List<RecentHost>, endpoint: Endpoint): RecentHost? =
        hosts.firstOrNull { it.endpoint == endpoint }

    fun encode(hosts: List<RecentHost>): String = hosts.joinToString(RECORD) {
        listOf(it.address, it.port.toString(), clean(it.name), clean(it.fingerprint))
            .joinToString(FIELD)
    }

    fun decode(text: String?): List<RecentHost> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(RECORD).mapNotNull { line ->
            val parts = line.split(FIELD)
            if (parts.size < 4) return@mapNotNull null
            val port = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (parts[0].isEmpty() || port !in 1..65535) return@mapNotNull null
            RecentHost(parts[0], port, parts[2], parts[3])
        }.take(MAX)
    }

    /** A device label is whatever someone typed in Settings; keep it off the delimiters. */
    private fun clean(value: String): String = value.replace(FIELD, " ").replace(RECORD, " ")
}
