package com.siliconprime.tabletmirror.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.databinding.ActivityViewerBinding
import com.siliconprime.tabletmirror.net.FrameHeader
import com.siliconprime.tabletmirror.net.HostStatus
import com.siliconprime.tabletmirror.net.Protocol
import com.siliconprime.tabletmirror.net.RemoteAction
import com.siliconprime.tabletmirror.net.TextInput
import com.siliconprime.tabletmirror.net.TextOp
import com.siliconprime.tabletmirror.net.TouchAction
import com.siliconprime.tabletmirror.net.TouchBatch
import com.siliconprime.tabletmirror.net.TouchPoint
import com.siliconprime.tabletmirror.net.VideoConfig
import com.siliconprime.tabletmirror.util.NetUtil
import com.siliconprime.tabletmirror.viewer.VideoDecoder
import com.siliconprime.tabletmirror.viewer.ViewerConnection

/**
 * Shows the remote screen and forwards input to it.
 *
 * Local gestures are translated into normalised host coordinates, so the two
 * tablets need not match in size or resolution. There is a view-only toggle
 * because a mirror you can accidentally tap is worse than one you cannot.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding

    private var connection: ViewerConnection? = null

    /** Written on the main thread, read by the connection's reader thread. */
    @Volatile
    private var decoder: VideoDecoder? = null

    /** Latest format from the host; replayed when the surface (re)appears. */
    private var pendingConfig: VideoConfig? = null
    private var surfaceReady = false

    private var controlAvailable = false
    private var viewOnly = false
    private var hostLabel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        if (address.isEmpty() || pin.length != Protocol.PIN_DIGITS) {
            finish()
            return
        }

        binding.surface.holder.addCallback(surfaceCallback)
        wireControls()
        setStatus(getString(R.string.viewer_connecting, address))

        connection = ViewerConnection(
            hostAddress = address,
            port = port,
            pin = pin,
            deviceName = NetUtil.deviceLabel(),
            listener = connectionListener,
        ).also { it.connect() }
    }

    override fun onDestroy() {
        connection?.disconnect()
        connection = null
        decoder?.stop()
        decoder = null
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------

    private fun wireControls() {
        binding.buttonBack.setOnClickListener { sendAction(RemoteAction.BACK) }
        binding.buttonHome.setOnClickListener { sendAction(RemoteAction.HOME) }
        binding.buttonRecents.setOnClickListener { sendAction(RemoteAction.RECENTS) }
        binding.buttonNotifications.setOnClickListener { sendAction(RemoteAction.NOTIFICATIONS) }
        binding.buttonBackspace.setOnClickListener {
            requireControl { connection?.sendText(TextInput(TextOp.BACKSPACE, "")) }
        }
        binding.buttonKeyboard.setOnClickListener { promptForText() }
        binding.buttonDisconnect.setOnClickListener { finish() }

        binding.buttonViewOnly.setOnClickListener {
            viewOnly = !viewOnly
            binding.buttonViewOnly.setText(
                if (viewOnly) R.string.action_enable_touch else R.string.action_view_only,
            )
            // Release anything currently held so the host is not left mid-drag.
            if (viewOnly) {
                connection?.sendTouch(TouchBatch(TouchAction.CANCEL, emptyList()))
            }
            refreshStatus()
        }

        binding.buttonToggleBar.setOnClickListener {
            val visible = binding.controlBar.visibility == View.VISIBLE
            binding.controlBar.visibility = if (visible) View.GONE else View.VISIBLE
        }

        binding.surface.setOnTouchListener { view, event -> forwardTouch(view, event) }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun promptForText() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.hint_text_to_send)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_send_text)
            .setMessage(R.string.message_send_text)
            .setView(field)
            .setPositiveButton(R.string.action_send) { _, _ ->
                val text = field.text?.toString().orEmpty()
                if (text.isNotEmpty()) {
                    requireControl { connection?.sendText(TextInput(TextOp.INSERT, text)) }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Runs [block] only if the host can actually act on it, else explains why. */
    private fun requireControl(block: () -> Unit) {
        if (!controlAvailable) {
            setStatus(getString(R.string.viewer_control_unavailable))
            return
        }
        block()
    }

    private fun sendAction(action: Int) = requireControl { connection?.sendGlobalAction(action) }

    private fun setStatus(text: String?) {
        if (text.isNullOrEmpty()) {
            binding.status.visibility = View.GONE
        } else {
            binding.status.visibility = View.VISIBLE
            binding.status.text = text
        }
    }

    private fun refreshStatus() {
        val parts = buildList {
            if (hostLabel.isNotEmpty()) add(hostLabel)
            if (!controlAvailable) add(getString(R.string.viewer_control_unavailable_short))
            if (viewOnly) add(getString(R.string.viewer_view_only))
        }
        // With control live and nothing to warn about, get out of the way.
        setStatus(if (parts.size <= 1 && controlAvailable && !viewOnly) null else parts.joinToString(" · "))
    }

    // -----------------------------------------------------------------------
    // Touch forwarding
    // -----------------------------------------------------------------------

    private fun forwardTouch(view: View, event: MotionEvent): Boolean {
        if (viewOnly) return false
        val connection = connection ?: return false
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false

        fun pointAt(index: Int): TouchPoint {
            val id = event.getPointerId(index).coerceIn(0, MAX_POINTER_ID)
            val x = (event.getX(index) / width).coerceIn(0f, 1f)
            val y = (event.getY(index) / height).coerceIn(0f, 1f)
            return TouchPoint(id, x, y)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                connection.sendTouch(
                    TouchBatch(TouchAction.DOWN, listOf(pointAt(event.actionIndex))),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                val points = (0 until event.pointerCount).map(::pointAt)
                connection.sendTouch(TouchBatch(TouchAction.MOVE, points))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                connection.sendTouch(
                    TouchBatch(TouchAction.UP, listOf(pointAt(event.actionIndex))),
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                connection.sendTouch(TouchBatch(TouchAction.CANCEL, emptyList()))
            }

            else -> return false
        }
        return true
    }

    // -----------------------------------------------------------------------
    // Video plumbing
    // -----------------------------------------------------------------------

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            pendingConfig?.let { startDecoder(it) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            // The decoder holds this surface; it must go before the surface does.
            decoder?.stop()
            decoder = null
        }
    }

    private fun startDecoder(config: VideoConfig) {
        decoder?.stop()
        decoder = null
        if (!surfaceReady) return

        binding.surface.setVideoSize(config.width, config.height)
        decoder = try {
            VideoDecoder(
                surface = binding.surface.holder.surface,
                width = config.width,
                height = config.height,
                csd = config.csd,
                onFirstFrame = { runOnUiThread { binding.progress.visibility = View.GONE } },
                onDecodeError = { error ->
                    runOnUiThread {
                        setStatus(getString(R.string.viewer_decode_error, error.message ?: ""))
                    }
                },
            ).also { it.start() }
        } catch (e: Exception) {
            setStatus(getString(R.string.viewer_decode_error, e.message ?: ""))
            null
        }
    }

    private val connectionListener = object : ViewerConnection.Listener {
        override fun onConnected(hostName: String) = runOnUiThread {
            hostLabel = hostName
            refreshStatus()
        }

        override fun onVideoConfig(config: VideoConfig) = runOnUiThread {
            // Also arrives when the host rotates, which changes the frame geometry.
            pendingConfig = config
            binding.progress.visibility = View.VISIBLE
            startDecoder(config)
        }

        override fun onFrame(payload: ByteArray) {
            if (payload.size <= FrameHeader.SIZE) return
            decoder?.submit(
                payload,
                FrameHeader.SIZE,
                payload.size - FrameHeader.SIZE,
                FrameHeader.readPts(payload),
            )
        }

        override fun onStatus(status: HostStatus) = runOnUiThread {
            controlAvailable = status.controlAvailable
            refreshStatus()
        }

        override fun onClosed(reason: String?, error: Boolean) = runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            decoder?.stop()
            decoder = null
            binding.progress.visibility = View.GONE
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle(if (error) R.string.title_connection_failed else R.string.title_disconnected)
                .setMessage(reason ?: getString(R.string.viewer_session_ended))
                .setPositiveButton(R.string.action_close) { _, _ -> finish() }
                .setOnDismissListener { finish() }
                .show()
        }
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PIN = "pin"

        /** GestureInjector supports ten simultaneous strokes. */
        private const val MAX_POINTER_ID = 9

        fun intent(context: Context, address: String, port: Int, pin: String): Intent =
            Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_ADDRESS, address)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PIN, pin)
            }
    }
}
