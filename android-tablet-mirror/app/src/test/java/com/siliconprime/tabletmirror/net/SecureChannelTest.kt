package com.siliconprime.tabletmirror.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class SecureChannelTest {

    private fun pair(): Pair<SecureChannel, SecureChannel> {
        val key = ByteArray(SecureChannel.KEY_BYTES) { it.toByte() }
        val host = SecureChannel(
            key,
            SecureChannel.DIR_HOST_TO_VIEWER,
            SecureChannel.DIR_VIEWER_TO_HOST,
        )
        val viewer = SecureChannel(
            key,
            SecureChannel.DIR_VIEWER_TO_HOST,
            SecureChannel.DIR_HOST_TO_VIEWER,
        )
        return host to viewer
    }

    @Test
    fun `messages round trip in both directions`() {
        val (host, viewer) = pair()

        val frame = ByteArray(2048) { (it % 251).toByte() }
        assertArrayEquals(
            frame,
            viewer.open(MsgType.VIDEO_FRAME.id, host.seal(MsgType.VIDEO_FRAME.id, frame)),
        )

        val touch = TouchBatch(TouchAction.DOWN, listOf(TouchPoint(0, 0.5f, 0.5f))).encode()
        assertArrayEquals(
            touch,
            host.open(MsgType.TOUCH.id, viewer.seal(MsgType.TOUCH.id, touch)),
        )
    }

    @Test
    fun `many messages stay in step`() {
        val (host, viewer) = pair()
        repeat(500) { i ->
            val plain = "frame $i".toByteArray()
            assertArrayEquals(
                plain,
                viewer.open(MsgType.VIDEO_FRAME.id, host.seal(MsgType.VIDEO_FRAME.id, plain)),
            )
        }
    }

    @Test
    fun `identical plaintext produces different ciphertext each time`() {
        val (host, viewer) = pair()
        val plain = ByteArray(64) { 7 }
        val first = host.seal(MsgType.VIDEO_FRAME.id, plain)
        val second = host.seal(MsgType.VIDEO_FRAME.id, plain)
        // Counter-derived IVs must never repeat, so neither may the ciphertext.
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(plain, viewer.open(MsgType.VIDEO_FRAME.id, first))
        assertArrayEquals(plain, viewer.open(MsgType.VIDEO_FRAME.id, second))
    }

    @Test
    fun `ciphertext carries the authentication tag`() {
        val (host, _) = pair()
        val plain = ByteArray(100)
        assertEquals(plain.size + SecureChannel.TAG_BYTES, host.seal(MsgType.VIDEO_FRAME.id, plain).size)
    }

    @Test
    fun `a tampered byte is rejected`() {
        val (host, viewer) = pair()
        val sealed = host.seal(MsgType.VIDEO_FRAME.id, ByteArray(32) { 1 })
        sealed[5] = (sealed[5].toInt() xor 0x01).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            viewer.open(MsgType.VIDEO_FRAME.id, sealed)
        }
    }

    @Test
    fun `relabelling a message to another type is rejected`() {
        val (host, viewer) = pair()
        // The message type is authenticated, so a frame cannot be replayed as input.
        val sealed = host.seal(MsgType.VIDEO_FRAME.id, ByteArray(16))
        assertThrows(GeneralSecurityException::class.java) {
            viewer.open(MsgType.TOUCH.id, sealed)
        }
    }

    @Test
    fun `a replayed message is rejected`() {
        val (host, viewer) = pair()
        val sealed = host.seal(MsgType.VIDEO_FRAME.id, ByteArray(16) { 9 })
        viewer.open(MsgType.VIDEO_FRAME.id, sealed)
        // The receiver has advanced its counter, so the same bytes no longer verify.
        assertThrows(GeneralSecurityException::class.java) {
            viewer.open(MsgType.VIDEO_FRAME.id, sealed)
        }
    }

    @Test
    fun `reordering two messages is rejected`() {
        val (host, viewer) = pair()
        val first = host.seal(MsgType.VIDEO_FRAME.id, "first".toByteArray())
        val second = host.seal(MsgType.VIDEO_FRAME.id, "second".toByteArray())
        assertThrows(GeneralSecurityException::class.java) {
            viewer.open(MsgType.VIDEO_FRAME.id, second)
        }
        // `first` is now unusable too: the counter advanced past it.
        assertThrows(GeneralSecurityException::class.java) {
            viewer.open(MsgType.VIDEO_FRAME.id, first)
        }
    }

    @Test
    fun `a peer using the wrong key cannot read the stream`() {
        val (host, _) = pair()
        val stranger = SecureChannel(
            ByteArray(SecureChannel.KEY_BYTES) { 42 },
            SecureChannel.DIR_VIEWER_TO_HOST,
            SecureChannel.DIR_HOST_TO_VIEWER,
        )
        val sealed = host.seal(MsgType.VIDEO_FRAME.id, ByteArray(16))
        assertThrows(GeneralSecurityException::class.java) {
            stranger.open(MsgType.VIDEO_FRAME.id, sealed)
        }
    }

    @Test
    fun `a channel refuses to send and receive on the same direction tag`() {
        val key = ByteArray(SecureChannel.KEY_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            SecureChannel(key, SecureChannel.DIR_HOST_TO_VIEWER, SecureChannel.DIR_HOST_TO_VIEWER)
        }
    }

    @Test
    fun `a short key is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureChannel(ByteArray(16), SecureChannel.DIR_HOST_TO_VIEWER, SecureChannel.DIR_VIEWER_TO_HOST)
        }
    }
}
