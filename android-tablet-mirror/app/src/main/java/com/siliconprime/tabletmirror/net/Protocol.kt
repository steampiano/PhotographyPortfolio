package com.siliconprime.tabletmirror.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Wire format shared by host and viewer.
 *
 * Every message is `[type:1][length:4][payload:length]`. Once the handshake
 * completes the payload bytes are the AES-GCM sealed form of the plaintext
 * payload, so `length` grows by [SecureChannel.TAG_BYTES].
 *
 * Nothing in this file touches the Android SDK: it is exercised directly by the
 * JVM unit tests in `src/test`.
 */
object Protocol {
    /** "TMR" + protocol generation, sent in the clear so mismatches fail fast. */
    const val MAGIC = 0x544D5232
    const val VERSION = 2

    const val DEFAULT_PORT = 45123

    /** mDNS/NSD service type used for host discovery on the local network. */
    const val SERVICE_TYPE = "_tabletmirror._tcp"
    const val NSD_ATTR_FINGERPRINT = "fp"

    /** Generous enough for a 4K keyframe, small enough to bound a hostile peer. */
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    /** Digits in the pairing comparison code. */
    const val SAS_DIGITS = 6

    /** BYE reason meaning "your identity is not pinned here". */
    const val REASON_NOT_PAIRED = "not-paired"
}

enum class MsgType(val id: Int) {
    /** viewer -> host: magic, version, ephemeral key, identity key, device name */
    HELLO(0x01),

    /** host -> viewer: ephemeral key, identity key, device name, pairing flag, signature */
    CHALLENGE(0x02),

    /** viewer -> host: signature over the transcript; encryption is live after this */
    AUTH(0x03),

    /** either direction, encrypted: whether this operator accepted the pairing code */
    PAIR_RESULT(0x04),

    /** host -> viewer: video dimensions + H.264 SPS/PPS */
    VIDEO_CONFIG(0x10),

    /** host -> viewer: one encoded access unit */
    VIDEO_FRAME(0x11),

    /** viewer -> host: batch of pointer positions in normalised coordinates */
    TOUCH(0x20),

    /** viewer -> host: back / home / recents / notifications */
    GLOBAL_ACTION(0x21),

    /** viewer -> host: text entry against the host's focused field */
    TEXT(0x22),

    /** host -> viewer: whether gesture injection is currently possible */
    STATUS(0x30),

    PING(0x40),
    PONG(0x41),

    /** either direction: orderly shutdown */
    BYE(0x4F),
    ;

    companion object {
        private val byId = entries.associateBy(MsgType::id)

        fun fromId(id: Int): MsgType? = byId[id]
    }
}

/** A decoded frame off the wire. [payload] is owned by the receiver. */
class Message(val type: MsgType, val payload: ByteArray)

// ---------------------------------------------------------------------------
// Payload codecs
// ---------------------------------------------------------------------------

/**
 * Opening message. The keys are X.509 encodings: [ephemeralKey] is thrown away
 * when the session ends, [identityKey] is the long-term one a peer pins.
 */
data class Hello(
    val magic: Int,
    val version: Int,
    val ephemeralKey: ByteArray,
    val identityKey: ByteArray,
    val deviceName: String,
) {
    fun encode(): ByteArray = buildPayload { out ->
        out.writeInt(magic)
        out.writeInt(version)
        out.writeBlob(ephemeralKey)
        out.writeBlob(identityKey)
        out.writeUTF(deviceName)
    }

    companion object {
        fun decode(payload: ByteArray): Hello = readPayload(payload) { inp ->
            Hello(inp.readInt(), inp.readInt(), inp.readBlob(), inp.readBlob(), inp.readUTF())
        }
    }
}

/**
 * The host's reply. [pairing] tells the viewer whether this is a first meeting;
 * it is covered by [signature], so it cannot be flipped in transit.
 */
data class Challenge(
    val ephemeralKey: ByteArray,
    val identityKey: ByteArray,
    val deviceName: String,
    val pairing: Boolean,
    val signature: ByteArray,
) {
    fun encode(): ByteArray = buildPayload { out ->
        out.writeBlob(ephemeralKey)
        out.writeBlob(identityKey)
        out.writeUTF(deviceName)
        out.writeBoolean(pairing)
        out.writeBlob(signature)
    }

    companion object {
        fun decode(payload: ByteArray): Challenge = readPayload(payload) { inp ->
            Challenge(
                inp.readBlob(),
                inp.readBlob(),
                inp.readUTF(),
                inp.readBoolean(),
                inp.readBlob(),
            )
        }
    }
}

/** The viewer's proof that it holds the identity key it announced. */
data class Auth(val signature: ByteArray) {
    fun encode(): ByteArray = buildPayload { it.writeBlob(signature) }

    companion object {
        fun decode(payload: ByteArray): Auth = readPayload(payload) { Auth(it.readBlob()) }
    }
}

/** One operator's verdict on the pairing code. */
data class PairResult(val accepted: Boolean) {
    fun encode(): ByteArray = buildPayload { it.writeBoolean(accepted) }

    companion object {
        fun decode(payload: ByteArray): PairResult =
            readPayload(payload) { PairResult(it.readBoolean()) }
    }
}

/**
 * Navigation actions the viewer can trigger on the host.
 *
 * Deliberately our own numbering rather than the framework's GLOBAL_ACTION_*
 * constants, so the wire format stays stable if those ever shift.
 */
object RemoteAction {
    const val BACK = 1
    const val HOME = 2
    const val RECENTS = 3
    const val NOTIFICATIONS = 4
    const val QUICK_SETTINGS = 5
}

/** Pointer stream actions, mirroring the subset of MotionEvent we forward. */
object TouchAction {
    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3
}

/** A single pointer sample. Coordinates are 0..1 fractions of the host screen. */
data class TouchPoint(val pointerId: Int, val x: Float, val y: Float)

data class TouchBatch(val action: Int, val points: List<TouchPoint>) {
    fun encode(): ByteArray = buildPayload { out ->
        out.writeByte(action)
        out.writeByte(points.size)
        for (p in points) {
            out.writeByte(p.pointerId)
            out.writeFloat(p.x)
            out.writeFloat(p.y)
        }
    }

    companion object {
        fun decode(payload: ByteArray): TouchBatch = readPayload(payload) { inp ->
            val action = inp.readUnsignedByte()
            val count = inp.readUnsignedByte()
            val points = ArrayList<TouchPoint>(count)
            repeat(count) {
                points.add(TouchPoint(inp.readUnsignedByte(), inp.readFloat(), inp.readFloat()))
            }
            TouchBatch(action, points)
        }
    }
}

/** Text operations the viewer can apply to the host's focused input. */
object TextOp {
    const val INSERT = 0
    const val BACKSPACE = 1
}

data class TextInput(val op: Int, val text: String) {
    fun encode(): ByteArray = buildPayload { out ->
        out.writeByte(op)
        out.writeUTF(text)
    }

    companion object {
        fun decode(payload: ByteArray): TextInput = readPayload(payload) { inp ->
            TextInput(inp.readUnsignedByte(), inp.readUTF())
        }
    }
}

data class VideoConfig(val width: Int, val height: Int, val csd: ByteArray) {
    fun encode(): ByteArray = buildPayload { out ->
        out.writeInt(width)
        out.writeInt(height)
        out.writeInt(csd.size)
        out.write(csd)
    }

    override fun equals(other: Any?): Boolean =
        other is VideoConfig && width == other.width && height == other.height &&
            csd.contentEquals(other.csd)

    override fun hashCode(): Int = (width * 31 + height) * 31 + csd.contentHashCode()

    companion object {
        fun decode(payload: ByteArray): VideoConfig = readPayload(payload) { inp ->
            val width = inp.readInt()
            val height = inp.readInt()
            val csdLen = inp.readInt()
            require(csdLen in 0..Protocol.MAX_MESSAGE_BYTES) { "bad csd length $csdLen" }
            val csd = ByteArray(csdLen)
            inp.readFully(csd)
            VideoConfig(width, height, csd)
        }
    }
}

/** Header carried in front of every encoded access unit. */
object FrameHeader {
    const val SIZE = 12
    const val FLAG_KEYFRAME = 1

    fun write(dest: ByteArray, ptsUs: Long, flags: Int) {
        require(dest.size >= SIZE) { "frame buffer too small" }
        for (i in 0 until 8) {
            dest[i] = (ptsUs ushr (56 - 8 * i)).toByte()
        }
        for (i in 0 until 4) {
            dest[8 + i] = (flags ushr (24 - 8 * i)).toByte()
        }
    }

    fun readPts(src: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (src[i].toLong() and 0xFF)
        }
        return v
    }

    fun readFlags(src: ByteArray): Int {
        var v = 0
        for (i in 0 until 4) {
            v = (v shl 8) or (src[8 + i].toInt() and 0xFF)
        }
        return v
    }
}

data class HostStatus(val controlAvailable: Boolean, val detail: String) {
    fun encode(): ByteArray = buildPayload { out ->
        out.writeBoolean(controlAvailable)
        out.writeUTF(detail)
    }

    companion object {
        fun decode(payload: ByteArray): HostStatus = readPayload(payload) { inp ->
            HostStatus(inp.readBoolean(), inp.readUTF())
        }
    }
}

/** Length-prefixed byte string, bounded so a hostile peer cannot force a huge allocation. */
internal fun DataOutputStream.writeBlob(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataInputStream.readBlob(): ByteArray {
    val size = readInt()
    require(size in 0..MAX_BLOB_BYTES) { "blob length out of range: $size" }
    return ByteArray(size).also(::readFully)
}

/** Keys and signatures are all a few hundred bytes; nothing legitimate is larger. */
private const val MAX_BLOB_BYTES = 8 * 1024

internal inline fun buildPayload(body: (DataOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use(body)
    return bytes.toByteArray()
}

internal inline fun <T> readPayload(payload: ByteArray, body: (DataInputStream) -> T): T =
    DataInputStream(payload.inputStream()).use(body)
