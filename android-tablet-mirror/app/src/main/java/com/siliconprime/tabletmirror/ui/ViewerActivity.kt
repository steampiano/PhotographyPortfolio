package com.siliconprime.tabletmirror.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.crypto.DeviceIdentity
import com.siliconprime.tabletmirror.crypto.PreferencesTrustStore
import com.siliconprime.tabletmirror.databinding.ActivityViewerBinding
import com.siliconprime.tabletmirror.net.FrameHeader
import com.siliconprime.tabletmirror.net.HostBrowser
import com.siliconprime.tabletmirror.net.HostStatus
import com.siliconprime.tabletmirror.net.PairingGate
import com.siliconprime.tabletmirror.net.Protocol
import com.siliconprime.tabletmirror.net.RemoteAction
import com.siliconprime.tabletmirror.net.TouchAction
import com.siliconprime.tabletmirror.net.TouchBatch
import com.siliconprime.tabletmirror.net.TouchPoint
import com.siliconprime.tabletmirror.net.VideoConfig
import com.siliconprime.tabletmirror.util.NetUtil
import com.siliconprime.tabletmirror.viewer.CandidateSweep
import com.siliconprime.tabletmirror.viewer.Endpoint
import com.siliconprime.tabletmirror.viewer.HostCandidates
import com.siliconprime.tabletmirror.viewer.RecentHost
import com.siliconprime.tabletmirror.viewer.ReconnectPolicy
import com.siliconprime.tabletmirror.viewer.SessionEnd
import com.siliconprime.tabletmirror.viewer.VideoDecoder
import com.siliconprime.tabletmirror.viewer.ViewerConnection
import com.siliconprime.tabletmirror.viewer.ViewerPrefs
import kotlinx.coroutines.launch

/**
 * Shows the remote screen and forwards input to it.
 *
 * Built to be left alone. Once paired, this screen reconnects on its own after a
 * Wi-Fi blip, a host restart or a power cut, and keeps trying indefinitely — a
 * tablet on a kitchen wall should never need someone to walk over and dismiss a
 * dialog. It also re-finds the host through discovery if its address has changed,
 * which is safe precisely because authentication is by pinned identity: a stranger
 * at that address cannot complete the handshake.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var prefs: ViewerPrefs
    private lateinit var trustStore: PreferencesTrustStore

    private var connection: ViewerConnection? = null

    /** Written on the main thread, read by the connection's reader thread. */
    @Volatile
    private var decoder: VideoDecoder? = null

    /** Latest format from the host; replayed when the surface (re)appears. */
    private var pendingConfig: VideoConfig? = null
    private var surfaceReady = false

    private var controlAvailable = false
    private var controlDetail = ""

    /** False until the host has said either way; "connecting" is not a refusal. */
    private var controlStatusKnown = false
    private var viewOnly = false
    private var hostLabel = ""

    /** This side's half of the pairing decision. */
    private val pairingGate = PairingGate()
    private var pairingDialog: AlertDialog? = null

    // Reconnection state.
    private val policy = ReconnectPolicy()
    private val sweep = CandidateSweep()
    private val handler = Handler(Looper.getMainLooper())
    private var browser: HostBrowser? = null
    private val discovered = LinkedHashSet<Endpoint>()
    private var candidates = mutableListOf<Endpoint>()
    private var candidateIndex = 0
    private var attempt = 0
    private var pairingRequested = false
    private var givenUp = false
    private var connectedOnce = false
    private var requestedEndpoint: Endpoint? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Which connection attempt is the live one. Read from the connection's own
     * threads, so volatile.
     */
    @Volatile
    private var sessionGeneration = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        prefs = ViewerPrefs(this)
        trustStore = PreferencesTrustStore(this)

        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        pairingRequested = intent.getBooleanExtra(EXTRA_PAIRING, false)
        if (address.isEmpty()) {
            finish()
            return
        }
        requestedEndpoint = Endpoint(address, port)

        binding.surface.holder.addCallback(surfaceCallback)
        wireControls()

        if (pairingRequested) pairingGate.openWindow()
        lifecycleScope.launch { pairingGate.pending.collect(::renderPairingRequest) }

        // Pairing is a deliberate, attended act, so it targets exactly the address
        // the operator chose. Roaming only begins once an identity is pinned.
        if (!pairingRequested) startDiscovery()

        candidates = mutableListOf(requestedEndpoint!!)
        connectToCurrentCandidate()
    }

    /**
     * Back on screen: get a picture up now, not after a backoff.
     *
     * Leaving the app to change the music and coming back should feel like the
     * mirror never went away, so this jumps any pending retry rather than waiting
     * one out.
     */
    override fun onStart() {
        super.onStart()
        if (givenUp || isFinishing || connection != null) return
        attempt = 0
        retryNow()
    }

    /**
     * Off screen: hang up, deliberately and immediately.
     *
     * Holding the socket open while backgrounded looks like the tolerant choice and
     * is the opposite. Android freezes a cached process, so nothing is read or sent
     * while the sockets stay open at the OS level — the host sees a viewer that has
     * simply gone silent, and only gives up on it after a read timeout. For that
     * window the single viewer slot is still held by a tablet that is not watching,
     * so coming straight back is refused with "already sharing to another tablet"
     * and has to be retried. Meanwhile the host has been encoding and sending video
     * to a screen nobody can see.
     *
     * Hanging up cleanly sends a FIN the host acts on at once. It frees the slot,
     * stops the streaming, and leaves the host in exactly the state it is designed
     * to sit in — sharing, waiting for a tablet — so the return trip is a fresh
     * connection to a host that is ready, rather than a race against a timeout.
     */
    override fun onStop() {
        if (!isFinishing) {
            handler.removeCallbacksAndMessages(RETRY_TOKEN)
            // Invalidate in-flight callbacks before tearing down, so the hang-up
            // cannot land on top of the connection that onStart is about to make.
            sessionGeneration++
            connection?.disconnect()
            connection = null
            decoder?.stop()
            decoder = null
            controlStatusKnown = false
            sweep.reset()
        }
        super.onStop()
    }

    override fun onDestroy() {
        givenUp = true
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        browser?.stop()
        browser = null
        pairingGate.closeWindow()
        connection?.disconnect()
        connection = null
        decoder?.stop()
        decoder = null
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Connection supervision
    // -----------------------------------------------------------------------

    private fun connectToCurrentCandidate() {
        if (givenUp || isFinishing) return
        val endpoint = candidates.getOrNull(candidateIndex) ?: run {
            scheduleRetry()
            return
        }

        setStatus(
            if (connectedOnce) {
                getString(R.string.viewer_reconnecting, hostLabel.ifEmpty { endpoint.address })
            } else {
                getString(R.string.viewer_connecting, endpoint.address)
            },
        )
        binding.progress.visibility = View.VISIBLE

        // Everything the previous attempt still has in flight is now stale.
        val generation = ++sessionGeneration
        connection = ViewerConnection(
            hostAddress = endpoint.address,
            port = endpoint.port,
            identity = DeviceIdentity.get(),
            trustStore = trustStore,
            pairingGate = pairingGate,
            deviceName = NetUtil.deviceLabel(),
            listener = listenerFor(generation),
        ).also { it.connect() }
    }

    private fun onAttemptFailed(reason: String?, end: SessionEnd) {
        connection = null
        controlStatusKnown = false
        decoder?.stop()
        decoder = null

        if (end == SessionEnd.LOCAL || givenUp || isFinishing) return

        sweep.record(end)

        // A refusal during a deliberate pairing attempt means the other tablet is not
        // offering to pair — its window is shut or has run out. Say so and stop.
        // Routing it through the unpaired path would send the operator back to the
        // chooser they just came from, to be told again what they already tried.
        if (pairingRequested && end == SessionEnd.NOT_PAIRED) {
            giveUp(reason)
            return
        }

        if (policy.isFatal(end, hasPairedHost = trustStore.all().isNotEmpty())) {
            giveUp(reason)
            return
        }

        // Work through the remaining candidates before backing off, so a host that
        // has merely changed address is found on this pass rather than the next.
        candidateIndex++
        if (candidateIndex < candidates.size) {
            connectToCurrentCandidate()
            return
        }

        // Nowhere left that could have been the host, and everywhere said the same
        // thing: this tablet has been unpaired. Silently retrying that forever is
        // what used to strand the kitchen with a countdown and no way out.
        if (sweep.allNotPaired) {
            showUnpaired()
            return
        }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (givenUp || isFinishing) return
        attempt++
        val delay = policy.delayFor(attempt)
        rebuildCandidates()
        candidateIndex = 0
        sweep.reset()
        registerNetworkCallback()

        binding.progress.visibility = View.GONE
        setStatus(
            getString(
                R.string.viewer_retrying,
                hostLabel.ifEmpty { requestedEndpoint?.address.orEmpty() },
                (delay / 1000).coerceAtLeast(1).toInt(),
            ),
        )
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        handler.postAtTime(
            { connectToCurrentCandidate() },
            RETRY_TOKEN,
            SystemClock.uptimeMillis() + delay,
        )
    }

    /** Jump the backoff when the network returns rather than waiting it out. */
    private fun retryNow() {
        if (givenUp || isFinishing || connection != null) return
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        rebuildCandidates()
        candidateIndex = 0
        sweep.reset()
        connectToCurrentCandidate()
    }

    private fun rebuildCandidates() {
        val saved = requestedEndpoint ?: prefs.lastEndpoint
        val ordered = HostCandidates.order(saved, discovered.toList())
        candidates = if (ordered.isEmpty() && saved != null) {
            mutableListOf(saved)
        } else {
            ordered.toMutableList()
        }
    }

    private fun giveUp(reason: String?) {
        givenUp = true
        binding.progress.visibility = View.GONE
        setStatus(null)
        AlertDialog.Builder(this)
            .setTitle(R.string.title_connection_failed)
            .setMessage(reason ?: getString(R.string.viewer_session_ended))
            .setPositiveButton(R.string.action_close) { _, _ -> finish() }
            .setNeutralButton(R.string.action_choose_other) { _, _ ->
                startActivity(ConnectActivity.pickIntent(this))
                finish()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    /**
     * Every address we know refused us: this tablet has been unpaired.
     *
     * No prompt, and nothing to keep trying — a refusal is not a blip that clears on
     * its own, so a retry countdown would only be a lie told slowly. Go straight to
     * the chooser with the address already filled in and pairing ticked, which is
     * the one sequence that can actually fix it.
     */
    private fun showUnpaired() {
        givenUp = true
        val endpoint = candidates.firstOrNull() ?: requestedEndpoint
        val name = hostLabel.ifEmpty { endpoint?.address.orEmpty() }
        startActivity(
            ConnectActivity.pickIntent(
                context = this,
                prefill = endpoint,
                pairing = true,
                message = getString(R.string.viewer_not_paired, name),
            ),
        )
        finish()
    }

    private fun startDiscovery() {
        if (browser != null) return
        browser = HostBrowser(this).apply {
            start(
                onFound = { host ->
                    runOnUiThread {
                        // Only an extra place to try. The pinned identity decides
                        // whether we will actually talk to whatever is there.
                        discovered.add(Endpoint(host.address, host.port))
                    }
                },
                onError = { /* A typed address remains the fallback. */ },
            )
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { retryNow() }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { networkCallback = null }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------

    private fun wireControls() {
        binding.buttonBack.setOnClickListener { sendBackToHost() }
        binding.buttonDisconnect.setOnClickListener { finish() }

        // The way out of a connection that is not coming back. Without it the only
        // exit was Disconnect, which lands on a screen that forwards straight to the
        // same unreachable tablet — a loop with no door.
        binding.buttonSwitch.setOnClickListener {
            givenUp = true
            startActivity(ConnectActivity.pickIntent(this))
            finish()
        }

        // The status line is the only thing on screen when input is refused, so it
        // is also where the explanation belongs.
        binding.status.setOnClickListener { showControlHelp() }

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
            val hiding = binding.controlBar.visibility == View.VISIBLE
            binding.controlBar.visibility = if (hiding) View.GONE else View.VISIBLE
            binding.buttonToggleBar.setText(
                if (hiding) R.string.action_view_controls else R.string.action_hide_controls,
            )
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
        // The video stays full-bleed; only the overlays move, so a display cutout or
        // a transient bar cannot sit on top of the controls or the status line.
        SystemBars.margin(binding.controlPill)
        SystemBars.margin(binding.status)
    }

    /**
     * Explains a refusal that this side cannot do anything about. The host reports
     * which of its two gates is shut, and this repeats that verbatim before adding
     * what to do about it, so the two tablets never disagree about the reason.
     */
    private fun showControlHelp() {
        if (controlAvailable || !controlStatusKnown) return
        val detail = controlDetail.ifEmpty { getString(R.string.viewer_control_unavailable) }
        AlertDialog.Builder(this)
            .setTitle(R.string.title_control_help)
            .setMessage(getString(R.string.control_help_body, detail))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun renderPairingRequest(request: PairingGate.Request?) {
        if (request == null) {
            pairingDialog?.dismiss()
            pairingDialog = null
            return
        }
        if (pairingDialog?.isShowing == true) return
        pairingDialog = PairingDialog.show(this, request, pairingGate)
    }

    /**
     * Sends Back to the other tablet.
     *
     * Spelled out as a button because the host's own Back may not be tappable in
     * the mirror at all: a tablet using gesture navigation shows no button, only an
     * edge swipe, and an injected swipe cannot trigger system navigation gestures.
     */
    private fun sendBackToHost() {
        if (!controlAvailable) {
            showControlHelp()
            return
        }
        connection?.sendGlobalAction(RemoteAction.BACK)
    }

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
            if (!controlAvailable) {
                add(controlDetail.ifEmpty { getString(R.string.viewer_control_unavailable_short) })
            }
            if (viewOnly) add(getString(R.string.viewer_view_only))
        }
        // Only offer the explanation when there is one to give.
        binding.status.isClickable = controlStatusKnown && !controlAvailable
        // With control live and nothing to warn about, get out of the way.
        setStatus(
            if (parts.size <= 1 && controlAvailable && !viewOnly) {
                null
            } else {
                parts.joinToString(" · ")
            },
        )
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

    /**
     * A listener bound to one connection attempt.
     *
     * Attempts can overlap: hanging up is asynchronous, so a callback from a
     * connection we have already abandoned can land after its replacement is live.
     * With a single shared listener that stale callback would null out the *new*
     * connection, or worse, push frames from the old stream into the new decoder —
     * two H.264 streams interleaved into one decoder is a corrupt picture, not a
     * dropped one. [generation] makes a stale callback a no-op.
     */
    private fun listenerFor(generation: Int) = object : ViewerConnection.Listener {

        private fun current(): Boolean = generation == sessionGeneration

        override fun onConnected(
            hostName: String,
            fingerprint: String,
            newlyPaired: Boolean,
        ) = runOnUiThread {
            if (!current()) return@runOnUiThread
            hostLabel = hostName
            attempt = 0
            connectedOnce = true
            sweep.reset()
            // Remember what worked, so next time the app opens straight into it. The
            // fingerprint rides along because it is what names the pin later.
            candidates.getOrNull(candidateIndex)?.let {
                prefs.remember(RecentHost(it.address, it.port, hostName, fingerprint))
            }
            unregisterNetworkCallback()
            if (newlyPaired) {
                setStatus(getString(R.string.viewer_paired, hostName, fingerprint))
                // Roaming is only safe once an identity is pinned, which it now is.
                startDiscovery()
            }
            refreshStatus()
        }

        override fun onVideoConfig(config: VideoConfig) = runOnUiThread {
            if (!current()) return@runOnUiThread
            // Also arrives when the host rotates, which changes the frame geometry.
            pendingConfig = config
            binding.progress.visibility = View.VISIBLE
            startDecoder(config)
        }

        override fun onFrame(payload: ByteArray) {
            // Not on the main thread, so this reads sessionGeneration directly. A
            // stale frame must never reach the live decoder.
            if (!current()) return
            if (payload.size <= FrameHeader.SIZE) return
            decoder?.submit(
                payload,
                FrameHeader.SIZE,
                payload.size - FrameHeader.SIZE,
                FrameHeader.readPts(payload),
                // The host has always flagged these; nothing on this side read it.
                FrameHeader.readFlags(payload) and FrameHeader.FLAG_KEYFRAME != 0,
            )
        }

        override fun onStatus(status: HostStatus) = runOnUiThread {
            if (!current()) return@runOnUiThread
            controlAvailable = status.controlAvailable
            controlDetail = status.detail
            controlStatusKnown = true
            refreshStatus()
        }

        override fun onClosed(reason: String?, end: SessionEnd) = runOnUiThread {
            if (!current()) return@runOnUiThread
            onAttemptFailed(reason, end)
        }
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_PAIRING = "pairing"

        /** GestureInjector supports ten simultaneous strokes. */
        private const val MAX_POINTER_ID = 9

        private val RETRY_TOKEN = Any()

        fun intent(context: Context, address: String, port: Int, pairing: Boolean): Intent =
            Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_ADDRESS, address)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_PAIRING, pairing)
            }
    }
}
