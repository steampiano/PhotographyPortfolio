package com.siliconprime.tabletmirror.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.siliconprime.tabletmirror.R
import com.siliconprime.tabletmirror.net.HostAdvertiser
import com.siliconprime.tabletmirror.net.HostStatus
import com.siliconprime.tabletmirror.net.Protocol
import com.siliconprime.tabletmirror.net.VideoConfig
import com.siliconprime.tabletmirror.ui.MainActivity
import com.siliconprime.tabletmirror.util.NetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Everything the host UI needs to render, published as it changes. */
data class HostState(
    val sharing: Boolean = false,
    val pin: String = "",
    val port: Int = Protocol.DEFAULT_PORT,
    val addresses: List<String> = emptyList(),
    val clientName: String? = null,
    val controlEnabled: Boolean = false,
    val message: String? = null,
)

/**
 * Owns the screen capture session for as long as this tablet is being shared.
 *
 * A foreground service is not optional here: [MediaProjection] on Android 10+
 * requires one, and from Android 14 the service must already be in the
 * foreground with the `mediaProjection` type *before* the projection is
 * obtained. The ongoing notification is also the honest signal to whoever is
 * holding this tablet that its screen is being sent elsewhere.
 *
 * The encoder only runs while a viewer is actually connected — capturing into a
 * hardware encoder with nobody watching would just burn battery.
 */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var server: HostServer? = null
    private var advertiser: HostAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var quality: Quality = Quality.BALANCED
    private var capturedWidth = 0
    private var capturedHeight = 0
    private var rotationRestartPending = false
    private var controlJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> if (projection == null) beginSharing(intent)
        }
        // The projection token cannot be recreated by the system, so never let
        // Android restart this service on its own.
        return START_NOT_STICKY
    }

    private fun beginSharing(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, Protocol.DEFAULT_PORT)
        quality = runCatching {
            Quality.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: Quality.BALANCED.name)
        }.getOrDefault(Quality.BALANCED)

        if (resultData == null || pin.length != Protocol.PIN_DIGITS) {
            publish { it.copy(message = getString(R.string.error_missing_permission)) }
            stopSelf()
            return
        }

        // Foreground first: getMediaProjection() throws otherwise on Android 14+.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val manager = getSystemService(MediaProjectionManager::class.java)
        val active = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (active == null) {
            publish { it.copy(message = getString(R.string.error_missing_permission)) }
            stopSelf()
            return
        }
        projection = active

        // Required from API 34 before any virtual display is created; also fires
        // when the user revokes capture from the system UI.
        active.registerCallback(projectionCallback, mainHandler)

        val deviceName = NetUtil.deviceLabel()
        val hostServer = HostServer(port, pin, deviceName, serverCallbacks)
        server = hostServer
        hostServer.start()

        advertiser = HostAdvertiser(this).apply {
            register(port, "$SERVICE_PREFIX $deviceName")
        }

        acquireWakeLock()
        registerDisplayListener()
        observeControlAvailability()

        publish {
            HostState(
                sharing = true,
                pin = pin,
                port = port,
                addresses = NetUtil.localAddresses(),
                clientName = null,
                controlEnabled = MirrorAccessibilityService.isEnabledInSettings(this),
                message = null,
            )
        }
    }

    private fun stopSharing() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        controlJob?.cancel()
        controlJob = null

        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        encoder?.stop()
        encoder = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        releaseWakeLock()
        MirrorAccessibilityService.releaseAllPointers()
        publish { HostState() }
    }

    override fun onDestroy() {
        stopSharing()
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Encoder lifecycle
    // -----------------------------------------------------------------------

    private fun startEncoder() {
        val active = projection ?: return
        if (encoder != null) return
        val metrics = realDisplayMetrics()
        capturedWidth = metrics.widthPixels
        capturedHeight = metrics.heightPixels
        if (capturedWidth <= 0 || capturedHeight <= 0) return

        encoder = try {
            ScreenEncoder(
                projection = active,
                displayWidth = capturedWidth,
                displayHeight = capturedHeight,
                densityDpi = metrics.densityDpi,
                quality = quality,
                listener = encoderListener,
            ).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "encoder failed to start", e)
            publish { it.copy(message = getString(R.string.error_encoder, e.message ?: "")) }
            null
        }
    }

    private fun stopEncoder() {
        encoder?.stop()
        encoder = null
    }

    /**
     * The encoder's input surface has a fixed size, so a rotation would arrive at
     * the viewer letterboxed inside the old aspect ratio. Rebuilding the encoder
     * at the new dimensions is the only clean fix; the viewer reconfigures its
     * decoder when the replacement VIDEO_CONFIG arrives.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (encoder == null || rotationRestartPending) return
            val metrics = realDisplayMetrics()
            if (metrics.widthPixels == capturedWidth && metrics.heightPixels == capturedHeight) {
                return
            }
            // Debounce: a rotation emits several changes as the animation settles.
            rotationRestartPending = true
            mainHandler.postDelayed({
                rotationRestartPending = false
                if (server?.hasClient == true) {
                    Log.i(TAG, "display geometry changed, rebuilding encoder")
                    stopEncoder()
                    startEncoder()
                }
            }, ROTATION_DEBOUNCE_MS)
        }
    }

    private fun registerDisplayListener() {
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, mainHandler)
    }

    private fun realDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(resources.displayMetrics)
        }
        return metrics
    }

    private val encoderListener = object : ScreenEncoder.Listener {
        override fun onFormat(width: Int, height: Int, csd: ByteArray) {
            server?.setVideoConfig(VideoConfig(width, height, csd))
        }

        override fun onFrame(buffer: ByteArray, length: Int) {
            server?.submitFrame(buffer, length)
        }

        override fun onError(error: Throwable) {
            Log.e(TAG, "encoder error", error)
            mainHandler.post {
                publish { it.copy(message = getString(R.string.error_encoder, error.message ?: "")) }
                stopEncoder()
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked screen capture from the system UI.
            Log.i(TAG, "projection stopped by the system")
            stopSharing()
            stopSelf()
        }
    }

    // -----------------------------------------------------------------------
    // Server callbacks
    // -----------------------------------------------------------------------

    private val serverCallbacks = object : HostServer.Callbacks {
        override fun onClientConnected(deviceName: String) {
            mainHandler.post {
                startEncoder()
                val controlEnabled = MirrorAccessibilityService.isAvailable.value
                server?.broadcastStatus(HostStatus(controlEnabled, controlDetail(controlEnabled)))
                publish { it.copy(clientName = deviceName, message = null) }
                updateNotification(deviceName)
            }
        }

        override fun onClientDisconnected(reason: String?) {
            mainHandler.post {
                stopEncoder()
                publish { it.copy(clientName = null, message = reason) }
                updateNotification(null)
            }
        }

        override fun onKeyFrameNeeded() {
            encoder?.requestKeyFrame()
        }

        override fun onServerError(error: Throwable) {
            Log.e(TAG, "server error", error)
            mainHandler.post {
                publish { it.copy(message = getString(R.string.error_server, error.message ?: "")) }
            }
        }
    }

    private fun observeControlAvailability() {
        controlJob?.cancel()
        controlJob = scope.launch {
            MirrorAccessibilityService.isAvailable.collect { available ->
                publish { it.copy(controlEnabled = available) }
                server?.broadcastStatus(HostStatus(available, controlDetail(available)))
            }
        }
    }

    private fun controlDetail(available: Boolean): String = if (available) {
        getString(R.string.status_control_ready)
    } else {
        getString(R.string.status_control_unavailable)
    }

    // -----------------------------------------------------------------------
    // Notification & wake lock
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(clientName: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (clientName == null) {
            getString(R.string.notification_waiting)
        } else {
            getString(R.string.notification_connected, clientName)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop_sharing), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(clientName: String?) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(clientName))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java) ?: return
        // Partial only: this keeps the stream alive through CPU idle. It cannot keep
        // the display on, so set a long screen timeout on the shared tablet — a
        // display that sleeps captures as black.
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun publish(transform: (HostState) -> HostState) {
        mutableState.value = transform(mutableState.value)
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        const val ACTION_START = "com.siliconprime.tabletmirror.START_SHARING"
        const val ACTION_STOP = "com.siliconprime.tabletmirror.STOP_SHARING"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_QUALITY = "quality"

        private const val CHANNEL_ID = "screen_sharing"
        private const val NOTIFICATION_ID = 42
        private const val SERVICE_PREFIX = "Tablet Mirror"
        private const val ROTATION_DEBOUNCE_MS = 400L
        private const val WAKE_LOCK_TAG = "TabletMirror:sharing"

        /** Safety net so a forgotten session cannot hold the CPU indefinitely. */
        private const val WAKE_LOCK_TIMEOUT_MS = 8L * 60 * 60 * 1000

        private val mutableState = MutableStateFlow(HostState())
        val state: StateFlow<HostState> = mutableState

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            pin: String,
            port: Int,
            quality: Quality,
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_PIN, pin)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_QUALITY, quality.name)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
