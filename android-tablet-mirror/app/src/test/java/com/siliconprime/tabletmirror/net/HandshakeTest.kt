package com.siliconprime.tabletmirror.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Exercises the real handshake over a loopback socket, so framing, encryption
 * switchover and failure reporting are all covered end to end.
 */
class HandshakeTest {

    private class Session(
        val hostChannel: MessageChannel,
        val viewerChannel: MessageChannel,
        val hostResult: Result<PeerInfo>,
        val viewerResult: Result<PeerInfo>,
        private val closeables: List<AutoCloseable>,
    ) : AutoCloseable {
        override fun close() = closeables.forEach { runCatching { it.close() } }
    }

    private fun handshake(hostPin: String, viewerPin: String): Session {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hostOutcome = ArrayBlockingQueue<Pair<Result<PeerInfo>, MessageChannel>>(1)

        val hostThread = Thread {
            val socket = server.accept()
            socket.tcpNoDelay = true
            val channel = MessageChannel(socket.getInputStream(), socket.getOutputStream())
            val result = runCatching { Handshake.asHost(channel, hostPin, "Host Tablet") }
            hostOutcome.put(result to channel)
        }
        hostThread.isDaemon = true
        hostThread.start()

        val clientSocket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        clientSocket.tcpNoDelay = true
        val viewerChannel = MessageChannel(
            clientSocket.getInputStream(),
            clientSocket.getOutputStream(),
        )
        val viewerResult = runCatching {
            Handshake.asViewer(viewerChannel, viewerPin, "Viewer Tablet")
        }

        val hostPair = hostOutcome.poll(20, TimeUnit.SECONDS)
        assertNotNull("host handshake did not finish", hostPair)

        return Session(
            hostChannel = hostPair!!.second,
            viewerChannel = viewerChannel,
            hostResult = hostPair.first,
            viewerResult = viewerResult,
            closeables = listOf(viewerChannel, clientSocket, server),
        )
    }

    @Test
    fun `matching pins authenticate both sides and identify the peer`() {
        handshake("482913", "482913").use { session ->
            val host = session.hostResult.getOrThrow()
            val viewer = session.viewerResult.getOrThrow()
            assertEquals("Viewer Tablet", host.deviceName)
            assertEquals("Host Tablet", viewer.deviceName)
            assertTrue(session.hostChannel.isEncrypted)
            assertTrue(session.viewerChannel.isEncrypted)
        }
    }

    @Test
    fun `traffic after the handshake is encrypted and readable`() {
        handshake("000001", "000001").use { session ->
            session.hostResult.getOrThrow()
            session.viewerResult.getOrThrow()

            val config = VideoConfig(1600, 1000, byteArrayOf(0, 0, 0, 1, 103, 77))
            session.hostChannel.send(MsgType.VIDEO_CONFIG, config.encode())
            val received = session.viewerChannel.receive()
            assertEquals(MsgType.VIDEO_CONFIG, received?.type)
            assertArrayEquals(config.csd, VideoConfig.decode(received!!.payload).csd)

            val touch = TouchBatch(TouchAction.DOWN, listOf(TouchPoint(1, 0.1f, 0.9f)))
            session.viewerChannel.send(MsgType.TOUCH, touch.encode())
            val inbound = session.hostChannel.receive()
            assertEquals(touch, TouchBatch.decode(inbound!!.payload))
        }
    }

    @Test
    fun `frames sent through the channel round trip after encryption starts`() {
        handshake("135790", "135790").use { session ->
            session.hostResult.getOrThrow()
            session.viewerResult.getOrThrow()

            val payload = ByteArray(FrameHeader.SIZE + 3000) { (it % 97).toByte() }
            FrameHeader.write(payload, 987_654L, FrameHeader.FLAG_KEYFRAME)
            session.hostChannel.sendFrame(payload, payload.size)

            val received = session.viewerChannel.receive()!!
            assertEquals(MsgType.VIDEO_FRAME, received.type)
            assertArrayEquals(payload, received.payload)
            assertEquals(987_654L, FrameHeader.readPts(received.payload))
        }
    }

    @Test
    fun `a frame shorter than its buffer is trimmed before sealing`() {
        handshake("246801", "246801").use { session ->
            session.hostResult.getOrThrow()
            session.viewerResult.getOrThrow()

            // The encoder hands over an oversized array with only a prefix in use.
            val buffer = ByteArray(4096)
            val used = FrameHeader.SIZE + 100
            FrameHeader.write(buffer, 42L, 0)
            session.hostChannel.sendFrame(buffer, used)

            val received = session.viewerChannel.receive()!!
            assertEquals(used, received.payload.size)
        }
    }

    @Test
    fun `a wrong pin is reported as a bad pin to both sides`() {
        handshake("111111", "222222").use { session ->
            val hostError = session.hostResult.exceptionOrNull()
            val viewerError = session.viewerResult.exceptionOrNull()

            assertTrue("host: $hostError", hostError is HandshakeException)
            assertEquals(
                HandshakeException.Reason.BAD_PIN,
                (hostError as HandshakeException).reason,
            )
            assertTrue("viewer: $viewerError", viewerError is HandshakeException)
            assertEquals(
                HandshakeException.Reason.BAD_PIN,
                (viewerError as HandshakeException).reason,
            )
        }
    }

    @Test
    fun `key derivation is deterministic and salted per session`() {
        val salt = ByteArray(Protocol.SALT_BYTES) { 1 }
        val viewerNonce = ByteArray(Protocol.NONCE_BYTES) { 2 }
        val hostNonce = ByteArray(Protocol.NONCE_BYTES) { 3 }

        val a = Handshake.deriveKey("123456", salt, viewerNonce, hostNonce)
        val b = Handshake.deriveKey("123456", salt, viewerNonce, hostNonce)
        assertArrayEquals(a, b)
        assertEquals(SecureChannel.KEY_BYTES, a.size)

        // A different nonce alone must change the key, so a recorded session
        // cannot be replayed against a later one even with the same PIN.
        val different = Handshake.deriveKey("123456", salt, viewerNonce, ByteArray(16) { 4 })
        assertTrue(!a.contentEquals(different))

        val otherPin = Handshake.deriveKey("123457", salt, viewerNonce, hostNonce)
        assertTrue(!a.contentEquals(otherPin))
    }

    @Test
    fun `generated pins are six digits`() {
        repeat(200) {
            val pin = Handshake.randomPin()
            assertEquals(Protocol.PIN_DIGITS, pin.length)
            assertTrue(pin, pin.all(Char::isDigit))
        }
    }

    @Test
    fun `proofs are domain separated by label and nonce order`() {
        val key = ByteArray(32) { it.toByte() }
        val a = ByteArray(16) { 1 }
        val b = ByteArray(16) { 2 }

        val viewerProof = Handshake.proof(key, "tabletmirror-viewer-proof", a, b)
        val hostProof = Handshake.proof(key, "tabletmirror-host-proof", b, a)
        // Otherwise a viewer's proof could be reflected back as the host's.
        assertTrue(!viewerProof.contentEquals(hostProof))
        assertEquals(Protocol.MAC_BYTES, viewerProof.size)
    }
}
