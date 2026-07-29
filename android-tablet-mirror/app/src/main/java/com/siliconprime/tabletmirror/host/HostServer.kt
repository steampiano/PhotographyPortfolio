package com.siliconprime.tabletmirror.host

import android.util.Log
import com.siliconprime.tabletmirror.net.FrameHeader
import com.siliconprime.tabletmirror.net.Handshake
import com.siliconprime.tabletmirror.net.HandshakeException
import com.siliconprime.tabletmirror.net.HostStatus
import com.siliconprime.tabletmirror.net.MessageChannel
import com.siliconprime.tabletmirror.net.MsgType
import com.siliconprime.tabletmirror.net.TextInput
import com.siliconprime.tabletmirror.net.TouchBatch
import com.siliconprime.tabletmirror.net.VideoConfig
import com.siliconprime.tabletmirror.net.buildPayload
import com.siliconprime.tabletmirror.net.readPayload
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accepts one viewer at a time, authenticates it with the session PIN, then
 * streams encoded frames to it and applies the input it sends back.
 *
 * Serving a single viewer is a deliberate limit: two controllers fighting over
 * one gesture injector would produce nonsense, and the encoder is tuned for one
 * consumer's bandwidth.
 */
class HostServer(
    private val port: Int,
    private val pin: String,
    private val deviceName: String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onClientConnected(deviceName: String)
        fun onClientDisconnected(reason: String?)

        /** The stream needs an IDR: a viewer just joined, or we dropped frames. */
        fun onKeyFrameNeeded()
        fun onServerError(error: Throwable)
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    /** Guards [client] and the outbound frame queue. */
    private val lock = Any()
    private var client: ClientSession? = null

    @Volatile
    private var videoConfig: VideoConfig? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ acceptLoop() }, "host-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Take the session out under the lock, but tear it down outside: closing
        // touches the socket, and stop() runs on the main thread. A courtesy BYE is
        // deliberately not sent — writing to a stalled socket could block the caller
        // for as long as TCP allows, and the viewer reports the closure from EOF
        // just as quickly.
        val active = synchronized(lock) { client.also { client = null } }
        active?.close()
    }

    /** Latest encoder output format; replayed to each viewer as it connects. */
    fun setVideoConfig(config: VideoConfig) {
        videoConfig = config
        synchronized(lock) {
            client?.let { session ->
                if (session.authenticated) {
                    session.enqueueConfig(config)
                    callbacks.onKeyFrameNeeded()
                }
            }
        }
    }

    fun submitFrame(buffer: ByteArray, length: Int) {
        synchronized(lock) { client?.enqueueFrame(buffer, length) }
    }

    fun broadcastStatus(status: HostStatus) {
        synchronized(lock) { client?.enqueueStatus(status) }
    }

    val hasClient: Boolean get() = synchronized(lock) { client?.authenticated == true }

    private fun acceptLoop() {
        try {
            val socket = ServerSocket(port)
            socket.reuseAddress = true
            serverSocket = socket
            while (running.get()) {
                val accepted = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running.get()) callbacks.onServerError(e)
                    break
                }
                Thread({ serve(accepted) }, "host-client").apply { isDaemon = true }.start()
            }
        } catch (e: IOException) {
            if (running.get()) callbacks.onServerError(e)
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    private fun serve(socket: Socket) {
        var session: ClientSession? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS

            val channel = MessageChannel(socket.getInputStream(), socket.getOutputStream())

            // Claim the single viewer slot before doing the expensive handshake.
            synchronized(lock) {
                if (client != null) {
                    runCatching {
                        channel.send(
                            MsgType.BYE,
                            buildPayload { it.writeUTF("Host is already sharing to another tablet.") },
                        )
                    }
                    throw IOException("busy")
                }
                session = ClientSession(socket, channel)
                client = session
            }
            val active = session!!

            val peer = Handshake.asHost(channel, pin, deviceName)
            active.markAuthenticated(peer.deviceName)
            callbacks.onClientConnected(peer.deviceName)

            videoConfig?.let { active.enqueueConfig(it) }
            callbacks.onKeyFrameNeeded()

            active.readLoop()
        } catch (e: HandshakeException) {
            Log.w(TAG, "handshake rejected: ${e.message}")
            session?.failureReason = e.message
        } catch (e: IOException) {
            if (e.message != "busy") Log.i(TAG, "client ended: ${e.message}")
        } catch (e: Exception) {
            callbacks.onServerError(e)
        } finally {
            val reason = session?.failureReason
            session?.close()
            runCatching { socket.close() }
            synchronized(lock) {
                if (client === session) client = null
            }
            if (session?.wasAuthenticated == true || reason != null) {
                MirrorAccessibilityService.releaseAllPointers()
                callbacks.onClientDisconnected(reason)
            }
        }
    }

    /** One connected viewer: a reader loop plus a dedicated, bounded sender. */
    private inner class ClientSession(
        private val socket: Socket,
        private val channel: MessageChannel,
    ) {
        @Volatile
        var authenticated = false
            private set

        @Volatile
        var wasAuthenticated = false
            private set

        @Volatile
        var failureReason: String? = null

        @Volatile
        private var closed = false

        private var peerName = ""

        /**
         * Outbound queue. Video is dropped rather than buffered without bound: a
         * viewer on a slow link must fall behind in time, not in latency. Control
         * messages are small and always kept.
         */
        private val queue = ArrayDeque<Outbound>()
        private val queueLock = Object()
        private var awaitingKeyFrame = true
        private var senderThread: Thread? = null

        fun markAuthenticated(name: String) {
            peerName = name
            authenticated = true
            wasAuthenticated = true
            senderThread = Thread({ sendLoop() }, "host-sender").apply {
                isDaemon = true
                start()
            }
        }

        fun enqueueConfig(config: VideoConfig) = offer(
            Outbound(MsgType.VIDEO_CONFIG, config.encode()),
            droppable = false,
        )

        fun enqueueStatus(status: HostStatus) = offer(
            Outbound(MsgType.STATUS, status.encode()),
            droppable = false,
        )

        fun enqueueFrame(buffer: ByteArray, length: Int) {
            if (!authenticated) return
            val keyframe = length >= FrameHeader.SIZE &&
                FrameHeader.readFlags(buffer) and FrameHeader.FLAG_KEYFRAME != 0

            synchronized(queueLock) {
                if (awaitingKeyFrame) {
                    // Everything before the next IDR is undecodable, so skip it.
                    if (!keyframe) return
                    awaitingKeyFrame = false
                }
                if (queue.size >= MAX_QUEUED_FRAMES) {
                    // Backlog means the link cannot carry this bitrate. Discard the
                    // pending video, ask for a fresh IDR, and resume cleanly rather
                    // than sending frames whose references we already dropped.
                    queue.removeAll { it.type == MsgType.VIDEO_FRAME }
                    awaitingKeyFrame = true
                    callbacks.onKeyFrameNeeded()
                    return
                }
                queue.addLast(Outbound(MsgType.VIDEO_FRAME, buffer, length))
                queueLock.notifyAll()
            }
        }

        private fun offer(message: Outbound, droppable: Boolean) {
            synchronized(queueLock) {
                if (closed) return
                if (droppable && queue.size >= MAX_QUEUED_FRAMES) return
                queue.addLast(message)
                queueLock.notifyAll()
            }
        }

        private fun sendLoop() {
            try {
                while (!closed) {
                    val next: Outbound = synchronized(queueLock) {
                        while (queue.isEmpty() && !closed) {
                            queueLock.wait(SENDER_WAIT_MS)
                        }
                        if (closed) return
                        queue.removeFirst()
                    }
                    if (next.type == MsgType.VIDEO_FRAME) {
                        channel.sendFrame(next.payload, next.length)
                    } else {
                        channel.send(next.type, next.payload)
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "sender stopped: ${e.message}")
            } finally {
                closeQuietly()
            }
        }

        fun readLoop() {
            while (!closed) {
                val message = channel.receive() ?: break
                when (message.type) {
                    MsgType.TOUCH -> MirrorAccessibilityService.deliverTouch(
                        TouchBatch.decode(message.payload),
                    )

                    MsgType.GLOBAL_ACTION -> {
                        val action = readPayload(message.payload) { it.readInt() }
                        MirrorAccessibilityService.deliverGlobalAction(action)
                    }

                    MsgType.TEXT -> MirrorAccessibilityService.deliverText(
                        TextInput.decode(message.payload),
                    )

                    MsgType.PING -> offer(Outbound(MsgType.PONG, message.payload), droppable = false)

                    MsgType.BYE -> return

                    else -> Log.d(TAG, "ignoring ${message.type} from viewer")
                }
            }
        }

        /** Tears the session down without writing to the socket. */
        fun close() {
            closeQuietly()
        }

        private fun closeQuietly() {
            closed = true
            authenticated = false
            synchronized(queueLock) {
                queue.clear()
                queueLock.notifyAll()
            }
            runCatching { channel.close() }
            runCatching { socket.close() }
        }
    }

    private class Outbound(
        val type: MsgType,
        val payload: ByteArray,
        val length: Int = payload.size,
    )

    companion object {
        private const val TAG = "HostServer"

        /** ~0.2s of video at 30fps: enough to ride out a hiccup, not enough to lag. */
        private const val MAX_QUEUED_FRAMES = 6

        private const val SENDER_WAIT_MS = 500L

        /** The viewer pings every 5s, so silence this long means it is gone. */
        private const val READ_TIMEOUT_MS = 20_000
    }
}
