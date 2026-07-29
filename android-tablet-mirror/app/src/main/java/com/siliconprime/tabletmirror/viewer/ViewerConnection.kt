package com.siliconprime.tabletmirror.viewer

import android.util.Log
import com.siliconprime.tabletmirror.crypto.IdentitySigner
import com.siliconprime.tabletmirror.crypto.TrustStore
import com.siliconprime.tabletmirror.net.Handshake
import com.siliconprime.tabletmirror.net.HandshakeException
import com.siliconprime.tabletmirror.net.HostStatus
import com.siliconprime.tabletmirror.net.Message
import com.siliconprime.tabletmirror.net.MessageChannel
import com.siliconprime.tabletmirror.net.MsgType
import com.siliconprime.tabletmirror.net.PairingGate
import com.siliconprime.tabletmirror.net.TextInput
import com.siliconprime.tabletmirror.net.TouchAction
import com.siliconprime.tabletmirror.net.TouchBatch
import com.siliconprime.tabletmirror.net.VideoConfig
import com.siliconprime.tabletmirror.net.buildPayload
import com.siliconprime.tabletmirror.net.readPayload
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The viewer's half of a session: connects, authenticates, pushes input up and
 * hands decoded-frame payloads to its listener.
 *
 * Reads happen on a dedicated thread. Writes are queued for a sender thread
 * rather than issued from the UI thread, because a socket write can block for as
 * long as the network takes and touch events originate from the main thread.
 */
class ViewerConnection(
    private val hostAddress: String,
    private val port: Int,
    private val identity: IdentitySigner,
    private val trustStore: TrustStore,
    private val pairingGate: PairingGate,
    private val deviceName: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(hostName: String, fingerprint: String, newlyPaired: Boolean)
        fun onVideoConfig(config: VideoConfig)

        /** [payload] begins with a FrameHeader. Ownership passes to the listener. */
        fun onFrame(payload: ByteArray)
        fun onStatus(status: HostStatus)

        /** Terminal. [reason] is set when the far end explained itself. */
        fun onClosed(reason: String?, error: Boolean)
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var channel: MessageChannel? = null

    private val outbound = ArrayDeque<Pair<MsgType, ByteArray>>()
    private val outboundLock = Object()

    @Volatile
    private var closedReported = false

    fun connect() {
        if (!running.compareAndSet(false, true)) return
        Thread({ run() }, "viewer-connection").apply { isDaemon = true }.start()
    }

    fun disconnect() {
        if (!running.compareAndSet(true, false)) return
        // No parting BYE: this is called from the activity's main thread, and a
        // socket write can block for as long as TCP allows. Closing the transport
        // gives the host an immediate EOF, on which it releases any held touch.
        closeTransport()
    }

    // -----------------------------------------------------------------------
    // Outbound input
    // -----------------------------------------------------------------------

    fun sendTouch(batch: TouchBatch) {
        // Stale MOVEs are worthless, so shed them under backpressure. DOWN and UP
        // must always survive or the host is left with a stuck or phantom pointer.
        enqueue(MsgType.TOUCH, batch.encode(), droppable = batch.action == TouchAction.MOVE)
    }

    fun sendGlobalAction(action: Int) {
        enqueue(MsgType.GLOBAL_ACTION, buildPayload { it.writeInt(action) }, droppable = false)
    }

    fun sendText(input: TextInput) {
        enqueue(MsgType.TEXT, input.encode(), droppable = false)
    }

    private fun enqueue(type: MsgType, payload: ByteArray, droppable: Boolean) {
        synchronized(outboundLock) {
            if (!running.get()) return
            if (droppable && outbound.size >= MAX_OUTBOUND) return
            if (outbound.size >= HARD_OUTBOUND_LIMIT) {
                outbound.removeFirst()
            }
            outbound.addLast(type to payload)
            outboundLock.notifyAll()
        }
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    private fun run() {
        var reason: String? = null
        var isError = true
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS

            val ch = MessageChannel(s.getInputStream(), s.getOutputStream())
            channel = ch

            val peer = Handshake.asViewer(
                channel = ch,
                identity = identity,
                trustStore = trustStore,
                authority = pairingGate,
                deviceName = deviceName,
            )
            listener.onConnected(peer.peerName, peer.fingerprint, peer.newlyPaired)

            Thread({ sendLoop(ch) }, "viewer-sender").apply { isDaemon = true }.start()

            readLoop(ch)
            reason = null
            isError = false
        } catch (e: HandshakeException) {
            reason = e.message
        } catch (e: SocketTimeoutException) {
            reason = "The host stopped responding."
        } catch (e: IOException) {
            reason = e.message ?: "Connection failed."
        } catch (e: Exception) {
            Log.e(TAG, "unexpected viewer failure", e)
            reason = e.message ?: "Unexpected error."
        } finally {
            running.set(false)
            closeTransport()
            reportClosed(reason, isError)
        }
    }

    private fun readLoop(channel: MessageChannel) {
        while (running.get()) {
            val message: Message = channel.receive() ?: return
            when (message.type) {
                MsgType.VIDEO_CONFIG -> listener.onVideoConfig(VideoConfig.decode(message.payload))
                MsgType.VIDEO_FRAME -> listener.onFrame(message.payload)
                MsgType.STATUS -> listener.onStatus(HostStatus.decode(message.payload))
                MsgType.PONG -> Unit
                MsgType.PING -> enqueue(MsgType.PONG, message.payload, droppable = false)
                MsgType.BYE -> {
                    val reason = runCatching {
                        readPayload(message.payload) { it.readUTF() }
                    }.getOrDefault("")
                    running.set(false)
                    reportClosed(reason.ifEmpty { null }, error = false)
                    return
                }

                else -> Log.d(TAG, "ignoring ${message.type} from host")
            }
        }
    }

    private fun sendLoop(channel: MessageChannel) {
        try {
            while (running.get()) {
                val next = synchronized(outboundLock) {
                    if (outbound.isEmpty()) {
                        outboundLock.wait(PING_INTERVAL_MS)
                    }
                    if (!running.get()) return
                    if (outbound.isEmpty()) null else outbound.removeFirst()
                }
                if (next == null) {
                    // Idle: a keepalive proves the link is still there, and the host
                    // treats prolonged silence as a dead viewer.
                    channel.send(MsgType.PING, buildPayload { it.writeLong(System.nanoTime()) })
                } else {
                    channel.send(next.first, next.second)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "sender stopped: ${e.message}")
            running.set(false)
            closeTransport()
        }
    }

    private fun closeTransport() {
        synchronized(outboundLock) {
            outbound.clear()
            outboundLock.notifyAll()
        }
        runCatching { channel?.close() }
        runCatching { socket?.close() }
    }

    private fun reportClosed(reason: String?, error: Boolean) {
        if (closedReported) return
        closedReported = true
        listener.onClosed(reason, error)
    }

    companion object {
        private const val TAG = "ViewerConnection"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val PING_INTERVAL_MS = 5_000L

        /** Beyond this the pointer stream is stale; drop new MOVEs. */
        private const val MAX_OUTBOUND = 24
        private const val HARD_OUTBOUND_LIMIT = 256
    }
}
