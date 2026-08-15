package com.siliconprime.tabletmirror.viewer

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the host's H.264 stream straight onto a [Surface].
 *
 * Output buffers are released for display the moment they decode rather than
 * being scheduled against their presentation timestamps. For a remote control
 * session, being current matters much more than being smooth — a buffered,
 * perfectly paced picture that lags half a second is unusable for tapping.
 */
class VideoDecoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val csd: ByteArray,
    private val onFirstFrame: () -> Unit,
    private val onDecodeError: (Throwable) -> Unit,
) {
    private class Frame(val data: ByteArray, val offset: Int, val size: Int, val ptsUs: Long)

    private val thread = HandlerThread("video-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    private var codec: MediaCodec? = null

    private val lock = Any()
    private val availableInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Frame>()

    /** Guarded by [lock], like the queue whose depth it is asked about. */
    private val gate = FrameGate()

    @Volatile
    private var stopped = false

    @Volatile
    private var renderedFirstFrame = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Tells the decoder not to build up a reordering pipeline.
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        val decoder = MediaCodec.createDecoderByType(MIME)
        codec = decoder
        decoder.setCallback(callback, handler)
        decoder.configure(format, surface, null, 0)
        decoder.start()
    }

    /**
     * [data] is a full access unit; [offset] skips the transport frame header.
     *
     * What may be queued is [FrameGate]'s decision, not this method's. Shedding an
     * arbitrary frame under load looks like the cheap option and is not: every frame
     * after it refers to something the decoder never received, so the picture stays
     * broken until the next keyframe — silently, with the connection still up.
     */
    fun submit(data: ByteArray, offset: Int, size: Int, ptsUs: Long, isKeyFrame: Boolean) {
        if (stopped || size <= 0) return
        synchronized(lock) {
            when (gate.offer(isKeyFrame, pending.size)) {
                FrameGate.Verdict.DECODE ->
                    pending.addLast(Frame(data, offset, size, ptsUs))

                FrameGate.Verdict.FLUSH_AND_DECODE -> {
                    pending.clear()
                    pending.addLast(Frame(data, offset, size, ptsUs))
                }

                FrameGate.Verdict.DROP_ALL -> {
                    pending.clear()
                    Log.i(TAG, "decoder fell behind; waiting for the next keyframe")
                }

                FrameGate.Verdict.DROP -> Unit
            }
        }
        drain()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.post {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            synchronized(lock) {
                pending.clear()
                availableInputs.clear()
            }
            thread.quitSafely()
        }
    }

    private fun drain() {
        val decoder = codec ?: return
        while (true) {
            val work = synchronized(lock) {
                if (stopped || pending.isEmpty() || availableInputs.isEmpty()) return
                availableInputs.removeFirst() to pending.removeFirst()
            }
            val (index, frame) = work
            try {
                val buffer = decoder.getInputBuffer(index)
                if (buffer == null) {
                    synchronized(lock) { availableInputs.addLast(index) }
                    return
                }
                buffer.clear()
                if (buffer.capacity() < frame.size) {
                    // Shouldn't happen with a sane stream; drop rather than crash.
                    Log.w(TAG, "input buffer too small for ${frame.size} bytes")
                    decoder.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                    continue
                }
                buffer.put(frame.data, frame.offset, frame.size)
                decoder.queueInputBuffer(index, 0, frame.size, frame.ptsUs, 0)
            } catch (e: IllegalStateException) {
                // The codec was released underneath us during teardown.
                if (!stopped) onDecodeError(e)
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) { availableInputs.addLast(index) }
            drain()
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            val render = info.size > 0 && !stopped
            runCatching { codec.releaseOutputBuffer(index, render) }
            if (render && !renderedFirstFrame) {
                renderedFirstFrame = true
                onFirstFrame()
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "decoder output format: $format")
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!stopped) onDecodeError(e)
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
