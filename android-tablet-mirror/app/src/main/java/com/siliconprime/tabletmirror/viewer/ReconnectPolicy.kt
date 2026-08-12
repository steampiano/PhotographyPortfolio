package com.siliconprime.tabletmirror.viewer

/** Somewhere to dial. */
data class Endpoint(val address: String, val port: Int) {
    override fun toString(): String = "$address:$port"
}

/** Why a session ended, which decides whether reconnecting makes sense. */
enum class SessionEnd {
    /** We hung up: the operator left the screen. */
    LOCAL,

    /** Network trouble, host restarting, host busy. Worth retrying forever. */
    TRANSIENT,

    /** Whoever answered is not a tablet we are paired with. */
    NOT_PAIRED,

    /** Definite misconfiguration. Retrying will not fix it; a person must look. */
    FATAL,
}

/**
 * When and where the viewer should try again.
 *
 * These tablets sit unattended in a working kitchen, so the default has to be
 * "get back on your own, indefinitely". Nobody should have to walk over and tap
 * a dialog because the Wi-Fi hiccuped or the till was restarted.
 *
 * Pure logic, no Android types, so the retry and candidate rules are unit tested.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
) {
    /**
     * Backoff for the given attempt, doubling up to a ceiling. The first couple of
     * retries are fast, because much the commonest case is a blip that has already
     * cleared; beyond that it settles into a slow poll that costs nothing to leave
     * running all day.
     */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return baseDelayMs
        val shift = (attempt - 1).coerceAtMost(MAX_SHIFT)
        val scaled = baseDelayMs shl shift
        // The shift is capped, but guard the arithmetic anyway.
        return if (scaled <= 0 || scaled > maxDelayMs) maxDelayMs else scaled
    }

    /**
     * Whether to give up. [hasPairedHost] matters for [SessionEnd.NOT_PAIRED]: with
     * nothing paired the app genuinely needs a person, but if this tablet *is*
     * paired then an unpaired answer usually means we reached the wrong device —
     * a recycled DHCP address, say — and another candidate may still be right.
     *
     * That is only true for a *single* refusal though. [CandidateSweep] decides the
     * other half: when every address tried in a pass says the same thing, the answer
     * is not a stray device, it is the host disowning us.
     */
    fun isFatal(end: SessionEnd, hasPairedHost: Boolean): Boolean = when (end) {
        SessionEnd.LOCAL -> true
        SessionEnd.FATAL -> true
        SessionEnd.NOT_PAIRED -> !hasPairedHost
        SessionEnd.TRANSIENT -> false
    }

    companion object {
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L

        /** Enough doubling to reach the ceiling, and no more. */
        private const val MAX_SHIFT = 16
    }
}

/**
 * The verdict of one full pass over the candidate list.
 *
 * This exists because "not paired" was previously invisible. Being unpaired on the
 * other tablet leaves this one retrying a host that will never let it in, forever,
 * with nothing on screen but a countdown — and no way out, because the connect
 * screen forwards straight back to the same host. Someone then has to walk to the
 * till and unpair from that end, which is exactly the errand this app is meant to
 * save.
 *
 * A single refusal is still not enough to conclude anything: with several addresses
 * in play, one of them may simply be a different device that picked up the host's
 * old DHCP lease. Only when *every* place tried in a pass gives the same answer is
 * it worth telling the operator, because at that point there is nowhere left that
 * could have been the real host.
 */
class CandidateSweep {

    private var attempted = 0
    private var notPaired = 0

    fun record(end: SessionEnd) {
        attempted++
        if (end == SessionEnd.NOT_PAIRED) notPaired++
    }

    fun reset() {
        attempted = 0
        notPaired = 0
    }

    /** Every address tried this pass answered "I do not know you". */
    val allNotPaired: Boolean get() = attempted > 0 && notPaired == attempted
}

/**
 * Builds the ordered list of places to try.
 *
 * The remembered address goes first because it is nearly always still right.
 * Discovered addresses follow, which is what recovers automatically when the host
 * picks up a new DHCP lease — the case that otherwise means someone reading an IP
 * off one tablet and typing it into another.
 *
 * Trying an address we were not explicitly given is safe *because* authentication
 * is by pinned identity: a stranger on that address cannot complete the handshake,
 * it simply fails and we move on.
 */
object HostCandidates {

    const val MAX_CANDIDATES = 8

    fun order(saved: Endpoint?, discovered: List<Endpoint>): List<Endpoint> {
        val ordered = LinkedHashSet<Endpoint>()
        saved?.let(ordered::add)
        ordered.addAll(discovered)
        return ordered.take(MAX_CANDIDATES)
    }
}
