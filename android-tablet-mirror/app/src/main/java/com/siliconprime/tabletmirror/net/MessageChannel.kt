package com.siliconprime.tabletmirror.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed message transport.
 *
 * Sends are serialised because the host writes video from the encoder thread
 * while also answering pings and status from the control thread. Receives are
 * expected to happen on exactly one thread per channel; [SecureChannel]'s
 * implicit sequence numbers make any other arrangement unsafe.
 */
class MessageChannel(
    input: InputStream,
    output: OutputStream,
) : Closeable {

    private val inp = DataInputStream(BufferedInputStream(input, BUFFER_BYTES))
    private val out = DataOutputStream(BufferedOutputStream(output, BUFFER_BYTES))

    private val sendLock = Any()

    @Volatile
    private var crypto: SecureChannel? = null

    /**
     * Switches both directions to authenticated encryption. Must be called at the
     * identical point in the handshake on both peers.
     */
    fun enableEncryption(channel: SecureChannel) {
        crypto = channel
    }

    val isEncrypted: Boolean get() = crypto != null

    fun send(type: MsgType, payload: ByteArray = EMPTY) {
        synchronized(sendLock) {
            val body = crypto?.seal(type.id, payload) ?: payload
            out.writeByte(type.id)
            out.writeInt(body.size)
            out.write(body)
            out.flush()
        }
    }

    /**
     * Sends a video frame without first copying it into a standalone payload
     * array. [buffer] holds a [FrameHeader] followed by `length - FrameHeader.SIZE`
     * bytes of encoded video.
     */
    fun sendFrame(buffer: ByteArray, length: Int) {
        synchronized(sendLock) {
            val active = crypto
            if (active == null) {
                out.writeByte(MsgType.VIDEO_FRAME.id)
                out.writeInt(length)
                out.write(buffer, 0, length)
            } else {
                // GCM needs the exact plaintext extent, so trim before sealing.
                val exact = if (length == buffer.size) buffer else buffer.copyOf(length)
                val body = active.seal(MsgType.VIDEO_FRAME.id, exact)
                out.writeByte(MsgType.VIDEO_FRAME.id)
                out.writeInt(body.size)
                out.write(body)
            }
            out.flush()
        }
    }

    /** Returns null on an orderly EOF; throws [IOException] on a corrupt stream. */
    fun receive(): Message? {
        val typeId = try {
            inp.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val type = MsgType.fromId(typeId)
            ?: throw IOException("unknown message type 0x${Integer.toHexString(typeId)}")

        val length = inp.readInt()
        if (length < 0 || length > Protocol.MAX_MESSAGE_BYTES) {
            throw IOException("message length out of range: $length")
        }
        val body = ByteArray(length)
        inp.readFully(body)

        val active = crypto ?: return Message(type, body)
        val plain = try {
            active.open(type.id, body)
        } catch (e: Exception) {
            // Wrong PIN, a tampered stream, or a desynchronised counter all land
            // here. None are recoverable, so surface it as a transport failure.
            throw IOException("failed to decrypt ${type.name}", e)
        }
        return Message(type, plain)
    }

    override fun close() {
        runCatching { out.flush() }
        runCatching { inp.close() }
        runCatching { out.close() }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private val EMPTY = ByteArray(0)
    }
}
