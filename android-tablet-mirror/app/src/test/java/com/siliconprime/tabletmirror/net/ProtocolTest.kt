package com.siliconprime.tabletmirror.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {

    @Test
    fun `touch batch survives a round trip`() {
        val batch = TouchBatch(
            TouchAction.MOVE,
            listOf(TouchPoint(0, 0.25f, 0.5f), TouchPoint(3, 0.99f, 0.001f)),
        )
        assertEquals(batch, TouchBatch.decode(batch.encode()))
    }

    @Test
    fun `touch batch with no points encodes`() {
        val batch = TouchBatch(TouchAction.CANCEL, emptyList())
        assertEquals(batch, TouchBatch.decode(batch.encode()))
    }

    @Test
    fun `all ten pointer ids round trip`() {
        val batch = TouchBatch(
            TouchAction.DOWN,
            (0..9).map { TouchPoint(it, it / 10f, 1f - it / 10f) },
        )
        assertEquals(batch, TouchBatch.decode(batch.encode()))
    }

    @Test
    fun `text input round trips including unicode`() {
        val input = TextInput(TextOp.INSERT, "hello — naïve 日本語")
        assertEquals(input, TextInput.decode(input.encode()))
    }

    @Test
    fun `video config round trips`() {
        val config = VideoConfig(1280, 800, byteArrayOf(0, 0, 0, 1, 103, 66, 42))
        val decoded = VideoConfig.decode(config.encode())
        assertEquals(1280, decoded.width)
        assertEquals(800, decoded.height)
        assertArrayEquals(config.csd, decoded.csd)
    }

    @Test
    fun `host status round trips`() {
        val status = HostStatus(true, "Remote control is available.")
        assertEquals(status, HostStatus.decode(status.encode()))
    }

    @Test
    fun `frame header carries pts and flags`() {
        val buffer = ByteArray(FrameHeader.SIZE + 4)
        FrameHeader.write(buffer, 1_234_567_890_123L, FrameHeader.FLAG_KEYFRAME)
        assertEquals(1_234_567_890_123L, FrameHeader.readPts(buffer))
        assertEquals(FrameHeader.FLAG_KEYFRAME, FrameHeader.readFlags(buffer))
    }

    @Test
    fun `frame header handles a zero timestamp and no flags`() {
        val buffer = ByteArray(FrameHeader.SIZE)
        FrameHeader.write(buffer, 0L, 0)
        assertEquals(0L, FrameHeader.readPts(buffer))
        assertEquals(0, FrameHeader.readFlags(buffer))
    }

    @Test
    fun `message types are uniquely addressable`() {
        val ids = MsgType.entries.map(MsgType::id)
        assertEquals(ids.size, ids.toSet().size)
        for (type in MsgType.entries) {
            assertEquals(type, MsgType.fromId(type.id))
        }
        // Every id must fit the single byte the wire format allocates.
        assertEquals(emptyList<MsgType>(), MsgType.entries.filter { it.id !in 0..255 })
    }
}
