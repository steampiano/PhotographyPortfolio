package com.siliconprime.tabletmirror.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class MessageChannelTest {

    private fun channelOver(bytes: ByteArray): MessageChannel =
        MessageChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream())

    @Test
    fun `plaintext messages frame and unframe`() {
        val out = ByteArrayOutputStream()
        val writer = MessageChannel(ByteArrayInputStream(ByteArray(0)), out)
        writer.send(MsgType.HELLO, byteArrayOf(1, 2, 3))
        writer.send(MsgType.PING, ByteArray(0))

        val reader = channelOver(out.toByteArray())
        val first = reader.receive()!!
        assertEquals(MsgType.HELLO, first.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.payload)

        val second = reader.receive()!!
        assertEquals(MsgType.PING, second.type)
        assertEquals(0, second.payload.size)

        assertNull("clean EOF should read as null", reader.receive())
    }

    @Test
    fun `an unknown message type is rejected`() {
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeByte(0x7E)
                writeInt(0)
            }
        }.toByteArray()
        assertThrows(IOException::class.java) { channelOver(bytes).receive() }
    }

    @Test
    fun `an absurd length is rejected before allocating`() {
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeByte(MsgType.VIDEO_FRAME.id)
                writeInt(Protocol.MAX_MESSAGE_BYTES + 1)
            }
        }.toByteArray()
        // A hostile peer must not be able to make us allocate an arbitrary buffer.
        assertThrows(IOException::class.java) { channelOver(bytes).receive() }
    }

    @Test
    fun `a negative length is rejected`() {
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeByte(MsgType.VIDEO_FRAME.id)
                writeInt(-1)
            }
        }.toByteArray()
        assertThrows(IOException::class.java) { channelOver(bytes).receive() }
    }

    @Test
    fun `a truncated payload is rejected`() {
        val bytes = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeByte(MsgType.VIDEO_FRAME.id)
                writeInt(64)
                write(ByteArray(10))
            }
        }.toByteArray()
        assertThrows(IOException::class.java) { channelOver(bytes).receive() }
    }

    @Test
    fun `a decryption failure surfaces as a transport error`() {
        val key = ByteArray(SecureChannel.KEY_BYTES) { 5 }
        val out = ByteArrayOutputStream()
        val writer = MessageChannel(ByteArrayInputStream(ByteArray(0)), out)
        writer.enableEncryption(
            SecureChannel(
                sendKey = key,
                recvKey = ByteArray(SecureChannel.KEY_BYTES) { 4 },
                sendDirection = SecureChannel.DIR_HOST_TO_VIEWER,
                recvDirection = SecureChannel.DIR_VIEWER_TO_HOST,
            ),
        )
        writer.send(MsgType.VIDEO_FRAME, ByteArray(32))

        val reader = channelOver(out.toByteArray())
        reader.enableEncryption(
            SecureChannel(
                sendKey = ByteArray(SecureChannel.KEY_BYTES) { 6 },
                recvKey = ByteArray(SecureChannel.KEY_BYTES) { 7 },
                sendDirection = SecureChannel.DIR_VIEWER_TO_HOST,
                recvDirection = SecureChannel.DIR_HOST_TO_VIEWER,
            ),
        )
        assertThrows(IOException::class.java) { reader.receive() }
    }

    @Test
    fun `sendFrame writes only the used prefix`() {
        val out = ByteArrayOutputStream()
        val writer = MessageChannel(ByteArrayInputStream(ByteArray(0)), out)
        val buffer = ByteArray(1000)
        FrameHeader.write(buffer, 7L, FrameHeader.FLAG_KEYFRAME)
        writer.sendFrame(buffer, FrameHeader.SIZE + 20)

        val message = channelOver(out.toByteArray()).receive()!!
        assertEquals(MsgType.VIDEO_FRAME, message.type)
        assertEquals(FrameHeader.SIZE + 20, message.payload.size)
        assertEquals(7L, FrameHeader.readPts(message.payload))
    }
}
