package com.siliconprime.tabletmirror.host

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.siliconprime.tabletmirror.net.FrameHeader
import com.siliconprime.tabletmirror.util.Geometry

/** Capture quality presets offered in the host UI. */
enum class Quality(
    val maxDimension: Int,
    val bitrate: Int,
    val frameRate: Int,
) {
    /** Kindest to a congested network. */
    LOW(960, 2_500_000, 24),
    BALANCED(1280, 6_000_000, 30),
    HIGH(1600, 10_000_000, 30),

    /**
     * Full HD at 60fps. The smoothest option, and the most demanding: not every
     * tablet's encoder can do 1080p60, so the host falls back automatically if
     * the codec refuses to configure.
     */
    FULL_HD_60(1920, 16_000_000, 60),

    /** No downscale — sharp text, needs a good 5GHz link. */
    NATIVE(0, 14_000_000, 30),
}

/**
 * Mirrors the default display into an H.264 elementary stream.
 *
 * The [MediaProjection] renders the screen into the encoder's input surface, so
 * no pixels are ever copied through the app process; frames arrive already
 * compressed on the codec's callback thread.
 *
 * The encoder surface has a fixed size, so a device rotation would otherwise be
 * squashed into the old aspect ratio. The owner is expected to tear this down
 * and build a new one with swapped dimensions instead — see
 * [ScreenCaptureService].
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val densityDpi: Int,
    private val quality: Quality,
    private val listener: Listener,
) {
    interface Listener {
        /** Called once, with the SPS/PPS the decoder needs before any frame. */
        fun onFormat(width: Int, height: Int, csd: ByteArray)

        /** [buffer] is a [FrameHeader] followed by one access unit. Not retained. */
        fun onFrame(buffer: ByteArray, length: Int)

        fun onError(error: Throwable)
    }

    private val thread = HandlerThread("screen-encoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var outputWidth = 0
    private var outputHeight = 0
    private var formatSent = false

    @Volatile
    private var stopped = false

    fun start() {
        val (w, h) = Geometry.encoderSize(displayWidth, displayHeight, quality.maxDimension)
        outputWidth = w
        outputHeight = h

        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, quality.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            // A perfectly static screen produces no new input frames, which would
            // leave a freshly connected viewer with nothing to decode and make
            // requestKeyFrame() a no-op. Re-submitting the last frame periodically
            // keeps the stream alive; on a still screen these cost almost nothing.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US)
        }

        val encoder = MediaCodec.createEncoderByType(MIME)
        codec = encoder
        encoder.setCallback(codecCallback, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val surface = encoder.createInputSurface()
        inputSurface = surface
        encoder.start()

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            w,
            h,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface,
            null,
            handler,
        )
        Log.i(TAG, "capturing ${displayWidth}x$displayHeight as ${w}x$h @ ${quality.frameRate}fps")
    }

    /** Asks the encoder for an immediate IDR, e.g. when a viewer connects. */
    fun requestKeyFrame() {
        handler.post {
            if (stopped) return@post
            runCatching {
                codec?.setParameters(
                    Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
                )
            }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        // Release off the codec callback thread to avoid deadlocking against a
        // callback that is already running.
        handler.post {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            runCatching { inputSurface?.release() }
            inputSurface = null
            thread.quitSafely()
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Unused: input arrives through the VirtualDisplay's surface.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            if (stopped) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)

                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val csd = ByteArray(info.size)
                        buffer.get(csd)
                        if (!formatSent) {
                            formatSent = true
                            listener.onFormat(outputWidth, outputHeight, csd)
                        }
                    } else {
                        val length = FrameHeader.SIZE + info.size
                        val out = ByteArray(length)
                        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        FrameHeader.write(
                            out,
                            info.presentationTimeUs,
                            if (keyframe) FrameHeader.FLAG_KEYFRAME else 0,
                        )
                        buffer.get(out, FrameHeader.SIZE, info.size)
                        listener.onFrame(out, length)
                    }
                }
            } catch (e: Exception) {
                listener.onError(e)
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Encoders may settle on dimensions of their own choosing.
            runCatching {
                outputWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                outputHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            }
            if (!formatSent) {
                val csd = concatCsd(format)
                if (csd != null) {
                    formatSent = true
                    listener.onFormat(outputWidth, outputHeight, csd)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener.onError(e)
        }
    }

    private fun concatCsd(format: MediaFormat): ByteArray? {
        val sps = runCatching { format.getByteBuffer("csd-0") }.getOrNull() ?: return null
        val pps = runCatching { format.getByteBuffer("csd-1") }.getOrNull()
        val spsBytes = ByteArray(sps.remaining()).also { sps.duplicate().get(it) }
        val ppsBytes = pps?.let { ByteArray(it.remaining()).also { b -> it.duplicate().get(b) } }
        return if (ppsBytes == null) spsBytes else spsBytes + ppsBytes
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val VIRTUAL_DISPLAY_NAME = "TabletMirror"
        private const val I_FRAME_INTERVAL_SEC = 2
        private const val REPEAT_FRAME_AFTER_US = 200_000L
    }
}
