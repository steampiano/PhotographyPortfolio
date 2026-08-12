package com.siliconprime.tabletmirror.net

import com.siliconprime.tabletmirror.crypto.Identity
import com.siliconprime.tabletmirror.crypto.IdentitySigner
import com.siliconprime.tabletmirror.crypto.InMemoryTrustStore
import com.siliconprime.tabletmirror.crypto.PairedPeer
import com.siliconprime.tabletmirror.crypto.SoftwareIdentity
import com.siliconprime.tabletmirror.crypto.TrustStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Exercises the real handshake over loopback sockets: ECDH, signatures, pinning,
 * pairing and the encrypted channel that follows.
 */
class HandshakeTest {

    /** Records the code it was shown and answers with a fixed verdict. */
    private class Authority(
        private val open: Boolean,
        private val accept: Boolean = true,
    ) : PairingAuthority {
        @Volatile
        var shownSas: String? = null

        override fun isPairingOpen() = open

        override fun confirmPairing(
            peerName: String,
            peerPublicKey: ByteArray,
            sas: String,
        ): Boolean {
            shownSas = sas
            return accept
        }
    }

    private class Ends(
        val hostResult: Result<HandshakeResult>,
        val viewerResult: Result<HandshakeResult>,
        val hostChannel: MessageChannel?,
        val viewerChannel: MessageChannel,
        private val closeables: List<AutoCloseable>,
    ) : AutoCloseable {
        override fun close() = closeables.forEach { runCatching { it.close() } }
    }

    private fun connect(
        hostIdentity: IdentitySigner = SoftwareIdentity(),
        viewerIdentity: IdentitySigner = SoftwareIdentity(),
        hostTrust: TrustStore = InMemoryTrustStore(),
        viewerTrust: TrustStore = InMemoryTrustStore(),
        hostAuthority: PairingAuthority = Authority(open = true),
        viewerAuthority: PairingAuthority = Authority(open = true),
    ): Ends {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hostOutcome = ArrayBlockingQueue<Pair<Result<HandshakeResult>, MessageChannel?>>(1)

        Thread {
            var channel: MessageChannel? = null
            val result = runCatching {
                val socket = server.accept()
                socket.tcpNoDelay = true
                channel = MessageChannel(socket.getInputStream(), socket.getOutputStream())
                Handshake.asHost(
                    channel!!,
                    hostIdentity,
                    hostTrust,
                    hostAuthority,
                    "Cashier Tablet",
                )
            }
            hostOutcome.put(result to channel)
        }.apply { isDaemon = true }.start()

        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        socket.tcpNoDelay = true
        val viewerChannel = MessageChannel(socket.getInputStream(), socket.getOutputStream())
        val viewerResult = runCatching {
            Handshake.asViewer(
                viewerChannel,
                viewerIdentity,
                viewerTrust,
                viewerAuthority,
                "Kitchen Tablet",
            )
        }

        if (viewerResult.isFailure) {
            // The host may still be waiting on a message that will now never
            // arrive. Closing first reproduces what it sees in the real app when a
            // viewer gives up: an EOF that unwinds the handshake.
            runCatching { viewerChannel.close() }
            runCatching { socket.close() }
        }

        val hostPair = hostOutcome.poll(30, TimeUnit.SECONDS)
        assertNotNull("host side did not finish", hostPair)
        return Ends(
            hostResult = hostPair!!.first,
            viewerResult = viewerResult,
            hostChannel = hostPair.second,
            viewerChannel = viewerChannel,
            closeables = listOf(viewerChannel, socket, server),
        )
    }

    private fun paired(
        hostIdentity: IdentitySigner,
        viewerIdentity: IdentitySigner,
    ): Pair<TrustStore, TrustStore> {
        val hostTrust = InMemoryTrustStore(
            listOf(PairedPeer(viewerIdentity.publicKey, "Kitchen Tablet", 1L)),
        )
        val viewerTrust = InMemoryTrustStore(
            listOf(PairedPeer(hostIdentity.publicKey, "Cashier Tablet", 1L)),
        )
        return hostTrust to viewerTrust
    }

    // -----------------------------------------------------------------------
    // Pairing
    // -----------------------------------------------------------------------

    @Test
    fun `first pairing succeeds and both sides see the same code`() {
        val hostAuthority = Authority(open = true)
        val viewerAuthority = Authority(open = true)
        connect(hostAuthority = hostAuthority, viewerAuthority = viewerAuthority).use { ends ->
            val host = ends.hostResult.getOrThrow()
            val viewer = ends.viewerResult.getOrThrow()

            assertTrue(host.newlyPaired)
            assertTrue(viewer.newlyPaired)
            assertEquals("Kitchen Tablet", host.peerName)
            assertEquals("Cashier Tablet", viewer.peerName)

            // The whole security of pairing rests on these matching.
            assertEquals(host.sas, viewer.sas)
            assertEquals(hostAuthority.shownSas, viewerAuthority.shownSas)
            assertEquals(Protocol.SAS_DIGITS, host.sas.length)
            assertTrue(host.sas.all(Char::isDigit))
        }
    }

    @Test
    fun `pairing pins each peer's identity on both sides`() {
        val hostIdentity = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()
        val hostTrust = InMemoryTrustStore()
        val viewerTrust = InMemoryTrustStore()

        connect(hostIdentity, viewerIdentity, hostTrust, viewerTrust).use { ends ->
            ends.hostResult.getOrThrow()
            ends.viewerResult.getOrThrow()
        }

        assertArrayEquals(viewerIdentity.publicKey, hostTrust.all().single().publicKey)
        assertArrayEquals(hostIdentity.publicKey, viewerTrust.all().single().publicKey)
    }

    @Test
    fun `an already paired pair reconnects with no pairing step`() {
        val hostIdentity = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()
        val (hostTrust, viewerTrust) = paired(hostIdentity, viewerIdentity)

        // Pairing is closed on both sides, as it is in normal operation.
        val hostAuthority = Authority(open = false)
        val viewerAuthority = Authority(open = false)

        connect(hostIdentity, viewerIdentity, hostTrust, viewerTrust, hostAuthority, viewerAuthority)
            .use { ends ->
                val host = ends.hostResult.getOrThrow()
                assertFalse(host.newlyPaired)
                assertFalse(ends.viewerResult.getOrThrow().newlyPaired)
                // No operator was ever prompted.
                assertEquals(null, hostAuthority.shownSas)
                assertEquals(null, viewerAuthority.shownSas)
            }
    }

    @Test
    fun `either side can veto the pairing code`() {
        connect(hostAuthority = Authority(open = true, accept = false)).use { ends ->
            assertEquals(
                HandshakeException.Reason.PAIRING_DECLINED,
                (ends.hostResult.exceptionOrNull() as HandshakeException).reason,
            )
            assertTrue(ends.viewerResult.isFailure)
        }

        connect(viewerAuthority = Authority(open = true, accept = false)).use { ends ->
            assertEquals(
                HandshakeException.Reason.PAIRING_DECLINED,
                (ends.viewerResult.exceptionOrNull() as HandshakeException).reason,
            )
            assertTrue(ends.hostResult.isFailure)
        }
    }

    @Test
    fun `a declined pairing pins nothing`() {
        val hostTrust = InMemoryTrustStore()
        val viewerTrust = InMemoryTrustStore()
        connect(
            hostTrust = hostTrust,
            viewerTrust = viewerTrust,
            viewerAuthority = Authority(open = true, accept = false),
        ).use {
            assertTrue(hostTrust.all().isEmpty())
            assertTrue(viewerTrust.all().isEmpty())
        }
    }

    // -----------------------------------------------------------------------
    // Refusing strangers
    // -----------------------------------------------------------------------

    @Test
    fun `an unpaired device is refused when the host is not pairing`() {
        connect(hostAuthority = Authority(open = false)).use { ends ->
            assertEquals(
                HandshakeException.Reason.NOT_PAIRED,
                (ends.hostResult.exceptionOrNull() as HandshakeException).reason,
            )
            val viewerError = ends.viewerResult.exceptionOrNull() as HandshakeException
            assertEquals(HandshakeException.Reason.NOT_PAIRED, viewerError.reason)
        }
    }

    @Test
    fun `a viewer not in pairing mode refuses an unknown host`() {
        // Stops an impostor from provoking a pairing prompt during normal use.
        connect(viewerAuthority = Authority(open = false)).use { ends ->
            assertEquals(
                HandshakeException.Reason.NOT_PAIRED,
                (ends.viewerResult.exceptionOrNull() as HandshakeException).reason,
            )
        }
    }

    @Test
    fun `an unsolicited offer to re-pair from a host that forgot us is refused`() {
        val hostIdentity = SoftwareIdentity()
        // The viewer still trusts the host, but the host's store was wiped.
        val viewerTrust = InMemoryTrustStore(
            listOf(PairedPeer(hostIdentity.publicKey, "Cashier Tablet", 1L)),
        )
        connect(
            hostIdentity = hostIdentity,
            viewerIdentity = SoftwareIdentity(),
            hostTrust = InMemoryTrustStore(),
            viewerTrust = viewerTrust,
            // Nobody asked to pair on this side, so an offer to replace the pin is
            // an offer from something that wants to be the host, not the host.
            viewerAuthority = Authority(open = false),
        ).use { ends ->
            assertEquals(
                HandshakeException.Reason.NOT_PAIRED,
                (ends.viewerResult.exceptionOrNull() as HandshakeException).reason,
            )
            // The stale pin survives, so nothing was quietly replaced.
            assertEquals(1, viewerTrust.all().size)
            assertArrayEquals(hostIdentity.publicKey, viewerTrust.all().first().publicKey)
        }
    }

    @Test
    fun `a host that forgot us can be re-paired without unpairing by hand`() {
        // The deadlock this replaced: unpairing on the host left the viewer holding
        // half a dead pin, and the viewer refused to re-pair while it held it — so
        // pairing could not be repaired from either tablet without a manual unpair on
        // both. With pairing deliberately opened here, the code comparison is the
        // same check that authorised the original pairing, so it may proceed.
        val hostIdentity = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()
        val viewerTrust = InMemoryTrustStore(
            listOf(PairedPeer(hostIdentity.publicKey, "Old Name", 1L)),
        )
        val hostTrust = InMemoryTrustStore()

        connect(
            hostIdentity = hostIdentity,
            viewerIdentity = viewerIdentity,
            hostTrust = hostTrust,
            viewerTrust = viewerTrust,
            hostAuthority = Authority(open = true),
            viewerAuthority = Authority(open = true),
        ).use { ends ->
            assertTrue(ends.viewerResult.isSuccess)
            assertTrue(ends.hostResult.isSuccess)
            assertTrue(ends.viewerResult.getOrThrow().newlyPaired)

            // Both sides end up pinned again, and the viewer's stale entry was
            // replaced rather than duplicated.
            assertEquals(1, viewerTrust.all().size)
            assertArrayEquals(hostIdentity.publicKey, viewerTrust.all().first().publicKey)
            assertEquals(1, hostTrust.all().size)
            assertArrayEquals(viewerIdentity.publicKey, hostTrust.all().first().publicKey)

            // Re-pairing refreshes the name rather than keeping the one it was
            // pinned under.
            assertEquals("Cashier Tablet", viewerTrust.all().first().name)
            assertEquals("Cashier Tablet", ends.viewerResult.getOrThrow().peerName)
        }
    }

    @Test
    fun `re-pairing still needs both codes to be confirmed`() {
        val hostIdentity = SoftwareIdentity()
        val viewerTrust = InMemoryTrustStore(
            listOf(PairedPeer(hostIdentity.publicKey, "Cashier Tablet", 1L)),
        )
        connect(
            hostIdentity = hostIdentity,
            viewerIdentity = SoftwareIdentity(),
            hostTrust = InMemoryTrustStore(),
            viewerTrust = viewerTrust,
            // Someone compares the codes and they differ.
            viewerAuthority = Authority(open = true, accept = false),
        ).use { ends ->
            assertEquals(
                HandshakeException.Reason.PAIRING_DECLINED,
                (ends.viewerResult.exceptionOrNull() as HandshakeException).reason,
            )
            // A refused comparison must not leave the old pin replaced.
            assertEquals(1, viewerTrust.all().size)
            assertArrayEquals(hostIdentity.publicKey, viewerTrust.all().first().publicKey)
        }
    }

    @Test
    fun `an impostor holding the right name but the wrong key cannot connect`() {
        val realHost = SoftwareIdentity()
        val impostor = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()

        // The viewer pinned the real host; the impostor answers instead.
        val viewerTrust = InMemoryTrustStore(
            listOf(PairedPeer(realHost.publicKey, "Cashier Tablet", 1L)),
        )
        val hostTrust = InMemoryTrustStore(
            listOf(PairedPeer(viewerIdentity.publicKey, "Kitchen Tablet", 1L)),
        )

        connect(
            hostIdentity = impostor,
            viewerIdentity = viewerIdentity,
            hostTrust = hostTrust,
            viewerTrust = viewerTrust,
            hostAuthority = Authority(open = false),
            viewerAuthority = Authority(open = false),
        ).use { ends ->
            val error = ends.viewerResult.exceptionOrNull() as HandshakeException
            // The impostor is simply not pinned, so it never gets a session.
            assertEquals(HandshakeException.Reason.NOT_PAIRED, error.reason)
        }
    }

    @Test
    fun `a stolen identity public key is not enough to impersonate a device`() {
        val realViewer = SoftwareIdentity()
        val thief = ForgedIdentity(claimedPublicKey = realViewer.publicKey)
        val hostIdentity = SoftwareIdentity()

        val hostTrust = InMemoryTrustStore(
            listOf(PairedPeer(realViewer.publicKey, "Kitchen Tablet", 1L)),
        )
        val viewerTrust = InMemoryTrustStore(
            listOf(PairedPeer(hostIdentity.publicKey, "Cashier Tablet", 1L)),
        )

        connect(
            hostIdentity = hostIdentity,
            viewerIdentity = thief,
            hostTrust = hostTrust,
            viewerTrust = viewerTrust,
            hostAuthority = Authority(open = false),
            viewerAuthority = Authority(open = false),
        ).use { ends ->
            // Announcing a pinned public key is easy; signing for it is not.
            assertEquals(
                HandshakeException.Reason.AUTH_FAILED,
                (ends.hostResult.exceptionOrNull() as HandshakeException).reason,
            )
        }
    }

    /** Claims someone else's public key while signing with its own. */
    private class ForgedIdentity(claimedPublicKey: ByteArray) : IdentitySigner {
        private val real = SoftwareIdentity()
        override val publicKey: ByteArray = claimedPublicKey
        override fun sign(data: ByteArray): ByteArray = real.sign(data)
    }

    // -----------------------------------------------------------------------
    // The channel that results
    // -----------------------------------------------------------------------

    @Test
    fun `traffic after the handshake is encrypted and round trips`() {
        val hostIdentity = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()
        val (hostTrust, viewerTrust) = paired(hostIdentity, viewerIdentity)

        connect(
            hostIdentity, viewerIdentity, hostTrust, viewerTrust,
            Authority(open = false), Authority(open = false),
        ).use { ends ->
            ends.hostResult.getOrThrow()
            ends.viewerResult.getOrThrow()
            val hostChannel = ends.hostChannel!!
            assertTrue(hostChannel.isEncrypted)
            assertTrue(ends.viewerChannel.isEncrypted)

            val config = VideoConfig(1280, 800, byteArrayOf(0, 0, 0, 1, 103, 77))
            hostChannel.send(MsgType.VIDEO_CONFIG, config.encode())
            val received = ends.viewerChannel.receive()!!
            assertEquals(MsgType.VIDEO_CONFIG, received.type)
            assertArrayEquals(config.csd, VideoConfig.decode(received.payload).csd)

            val touch = TouchBatch(TouchAction.DOWN, listOf(TouchPoint(0, 0.5f, 0.5f)))
            ends.viewerChannel.send(MsgType.TOUCH, touch.encode())
            assertEquals(touch, TouchBatch.decode(hostChannel.receive()!!.payload))
        }
    }

    @Test
    fun `frames round trip through the encrypted channel`() {
        val hostIdentity = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()
        val (hostTrust, viewerTrust) = paired(hostIdentity, viewerIdentity)

        connect(
            hostIdentity, viewerIdentity, hostTrust, viewerTrust,
            Authority(open = false), Authority(open = false),
        ).use { ends ->
            ends.hostResult.getOrThrow()
            ends.viewerResult.getOrThrow()

            // Oversized buffer with only a prefix in use, as the encoder hands over.
            val buffer = ByteArray(4096)
            val used = FrameHeader.SIZE + 512
            FrameHeader.write(buffer, 4242L, FrameHeader.FLAG_KEYFRAME)
            ends.hostChannel!!.sendFrame(buffer, used)

            val received = ends.viewerChannel.receive()!!
            assertEquals(MsgType.VIDEO_FRAME, received.type)
            assertEquals(used, received.payload.size)
            assertEquals(4242L, FrameHeader.readPts(received.payload))
        }
    }

    @Test
    fun `each session derives fresh keys, so past traffic stays sealed`() {
        val hostIdentity = SoftwareIdentity()
        val viewerIdentity = SoftwareIdentity()
        val (hostTrust, viewerTrust) = paired(hostIdentity, viewerIdentity)

        val codes = (1..3).map {
            connect(
                hostIdentity, viewerIdentity, hostTrust, viewerTrust,
                Authority(open = false), Authority(open = false),
            ).use { ends -> ends.hostResult.getOrThrow().sas }
        }
        // The comparison code is derived from the session keys, so distinct codes
        // across reconnections with identical identities demonstrate that the keys
        // are ephemeral rather than a function of the long-term keys.
        assertEquals(3, codes.toSet().size)
    }

    // -----------------------------------------------------------------------
    // Transcript binding
    // -----------------------------------------------------------------------

    @Test
    fun `the transcript covers every field of the exchange`() {
        val base = listOf(
            ByteArray(8) { 1 }, ByteArray(8) { 2 }, ByteArray(8) { 3 }, ByteArray(8) { 4 },
        )
        fun hash(
            version: Int = 2,
            fields: List<ByteArray> = base,
            pairing: Boolean = false,
        ) = Handshake.transcript(version, fields[0], fields[1], fields[2], fields[3], pairing)

        val reference = hash()
        assertFalse("version must be bound", reference.contentEquals(hash(version = 3)))
        assertFalse("pairing flag must be bound", reference.contentEquals(hash(pairing = true)))

        for (i in base.indices) {
            val altered = base.toMutableList().also { it[i] = ByteArray(8) { 9 } }
            assertFalse("field $i must be bound", reference.contentEquals(hash(fields = altered)))
        }

        // Length prefixing must stop adjacent fields from being re-split.
        val split = listOf(ByteArray(4) { 1 }, ByteArray(12) { 1 }, base[2], base[3])
        val joined = listOf(ByteArray(8) { 1 }, ByteArray(8) { 1 }, base[2], base[3])
        assertFalse(hash(fields = split).contentEquals(hash(fields = joined)))
    }

    @Test
    fun `key derivation binds to the transcript and separates the directions`() {
        val secret = ByteArray(32) { 5 }
        val transcript = ByteArray(32) { 6 }
        val keys = Handshake.deriveKeys(secret, transcript)

        assertEquals(SecureChannel.KEY_BYTES, keys.hostToViewer.size)
        assertEquals(SecureChannel.KEY_BYTES, keys.viewerToHost.size)
        assertFalse(keys.hostToViewer.contentEquals(keys.viewerToHost))

        val other = Handshake.deriveKeys(secret, ByteArray(32) { 7 })
        assertFalse(keys.hostToViewer.contentEquals(other.hostToViewer))
        assertFalse("a different transcript must give a different code", keys.sas == other.sas)
    }

    @Test
    fun `the comparison code is always six digits`() {
        for (i in 0 until 500) {
            val sas = Handshake.sasFrom(ByteArray(8) { (i + it).toByte() })
            assertEquals(Protocol.SAS_DIGITS, sas.length)
            assertTrue(sas, sas.all(Char::isDigit))
        }
        assertEquals("000000", Handshake.sasFrom(ByteArray(8)))
    }

    @Test
    fun `fingerprints are stable, keyed to the key, and human readable`() {
        val identity = SoftwareIdentity()
        val fingerprint = Identity.fingerprint(identity.publicKey)
        assertEquals(fingerprint, Identity.fingerprint(identity.publicKey))
        assertEquals(19, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9A-F]{4}( [0-9A-F]{4}){3}")))
        assertFalse(fingerprint == Identity.fingerprint(SoftwareIdentity().publicKey))
    }
}
