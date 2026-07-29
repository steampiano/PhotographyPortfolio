package com.siliconprime.tabletmirror.net

import com.siliconprime.tabletmirror.crypto.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridges the handshake's need for a human decision to the UI.
 *
 * Two separate guarantees live here:
 *
 *  - **Pairing is closed by default.** [isPairingOpen] only returns true inside a
 *    short window that an operator deliberately opened. Outside it, an unknown
 *    device is refused before any key agreement happens, so a tablet quietly
 *    doing its job can never be talked into pairing with a stranger.
 *  - **A code is compared, not typed.** [confirmPairing] blocks the handshake
 *    thread while the code is displayed, and only proceeds if someone confirms it
 *    matches the other tablet's screen.
 */
open class PairingGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
) : PairingAuthority {

    /** A code awaiting comparison against the other tablet. */
    data class Request(
        val peerName: String,
        val peerPublicKey: ByteArray,
        val fingerprint: String,
        val sas: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Request && sas == other.sas && peerPublicKey.contentEquals(other.peerPublicKey)

        override fun hashCode(): Int = 31 * sas.hashCode() + peerPublicKey.contentHashCode()
    }

    private val pendingState = MutableStateFlow<Request?>(null)

    /** Non-null while a code is on screen waiting to be confirmed. */
    val pending: StateFlow<Request?> = pendingState

    private val windowState = MutableStateFlow(0L)

    /** When the current pairing window expires, or 0 if pairing is closed. */
    val windowClosesAt: StateFlow<Long> = windowState

    private val lock = Object()
    private var answer: Boolean? = null

    fun openWindow(durationMs: Long = DEFAULT_WINDOW_MS) {
        windowState.value = clock() + durationMs
    }

    fun closeWindow() {
        windowState.value = 0L
        respond(accept = false)
    }

    fun remainingWindowMs(): Long = (windowState.value - clock()).coerceAtLeast(0L)

    override fun isPairingOpen(): Boolean = clock() < windowState.value

    override fun confirmPairing(
        peerName: String,
        peerPublicKey: ByteArray,
        sas: String,
    ): Boolean {
        synchronized(lock) { answer = null }
        pendingState.value = Request(
            peerName = peerName,
            peerPublicKey = peerPublicKey,
            fingerprint = Identity.fingerprint(peerPublicKey),
            sas = sas,
        )
        try {
            // Deliberately real time rather than the injected clock: this bounds an
            // actual blocking wait, and a clock a caller can freeze would spin here
            // forever.
            val deadline = System.currentTimeMillis() + confirmTimeoutMs
            synchronized(lock) {
                while (answer == null) {
                    val remaining = deadline - System.currentTimeMillis()
                    // An unanswered prompt must not pin a stranger or hold the
                    // connection thread open indefinitely.
                    if (remaining <= 0) return false
                    lock.wait(remaining)
                }
                return answer == true
            }
        } finally {
            pendingState.value = null
            // One window, one pairing: close it so a second device cannot slip in
            // behind the first.
            windowState.value = 0L
        }
    }

    /** Called from the UI when an operator answers the prompt. */
    fun respond(accept: Boolean) {
        synchronized(lock) {
            answer = accept
            lock.notifyAll()
        }
    }

    companion object {
        /** Long enough to walk between two tablets, short enough to be deliberate. */
        const val DEFAULT_WINDOW_MS = 120_000L
        private const val CONFIRM_TIMEOUT_MS = 120_000L
    }
}
