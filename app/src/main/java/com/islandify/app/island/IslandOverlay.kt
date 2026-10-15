package com.islandify.app.island

import com.islandify.app.R
import com.islandify.app.core.*

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Owns the island window (a ComposeView) for one host service.
 *
 * [windowType] decides where the window sits:
 *  - TYPE_ACCESSIBILITY_OVERLAY: ABOVE the status bar, so taps reach the island everywhere.
 *    Used by [IslandA11yService].
 *  - TYPE_APPLICATION_OVERLAY: Android places this BELOW the status bar window, so any tap
 *    inside the status bar strip is swallowed by SystemUI and never reaches the island.
 *    Only used as a fallback by [IslandService].
 *
 * Also listens for charging / Bluetooth / headphone events while the island is shown.
 */
class IslandOverlay(
    private val host: Context,
    private val windowType: Int,
) {
    private val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density get() = host.resources.displayMetrics.density

    private var owner: OverlayOwner? = null
    private var scope: CoroutineScope? = null
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var fullscreenProbe: View? = null
    private var receiversOn = false
    private val otherAppFullscreen = MutableStateFlow(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sawVisibleStatusBar = false
    private var fullscreenCandidate = false
    private var fullscreenConfirmation: Runnable? = null

    val shown: Boolean get() = view != null

    /** Debounce system-bar animations and never hide before one reliable visible-bar reading. */
    private fun updateFullscreenCandidate(hidden: Boolean) {
        fullscreenCandidate = hidden
        fullscreenConfirmation?.let(mainHandler::removeCallbacks)
        fullscreenConfirmation = null
        if (!hidden) {
            sawVisibleStatusBar = true
            otherAppFullscreen.value = false
            return
        }
        if (!sawVisibleStatusBar) {
            // Starting an overlay must never make the island disappear on an unknown reading.
            otherAppFullscreen.value = false
            return
        }
        val confirm = Runnable {
            if (fullscreenCandidate) otherAppFullscreen.value = true
        }
        fullscreenConfirmation = confirm
        mainHandler.postDelayed(confirm, 350L)
    }

    /**
     * The island window deliberately draws through the status-bar/cutout area. Its own insets can
     * therefore report a zero top inset even while the bar is visible. A separate transparent,
     * non-touchable 1 px window omits FLAG_LAYOUT_NO_LIMITS and receives the real bar visibility.
     * Insets/layout callbacks are event-driven: no screen contents, app lists or polling are used.
     */
    private fun startFullscreenProbe() {
        if (fullscreenProbe != null) return
        val probe = View(host).apply {
            alpha = 0f
            setOnApplyWindowInsetsListener { root, insets ->
                val hidden = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Insets delivered to an accessibility overlay can claim that status bars are
                    // hidden even on the home screen. Cross-check the service metrics and the
                    // position of this normally laid-out probe; any visible signal keeps the island.
                    val probeVisible = insets.isVisible(WindowInsets.Type.statusBars())
                    val metricsVisible = runCatching {
                        wm.currentWindowMetrics.windowInsets
                            .isVisible(WindowInsets.Type.statusBars())
                    }.getOrDefault(true)
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    val geometryVisible = location[1] > 0
                    !(probeVisible || metricsVisible || geometryVisible)
                } else {
                    @Suppress("DEPRECATION")
                    val currentTop = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    val stableTop = insets.stableInsetTop
                    @Suppress("DEPRECATION")
                    val fullscreenFlag =
                        root.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
                    // If an OEM cannot provide a stable status-bar height, fail open rather than
                    // hiding the island outside full screen.
                    fullscreenFlag || (stableTop > 0 && currentTop == 0)
                }
                updateFullscreenCandidate(hidden)
                insets
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = view.requestApplyInsets()
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
            @Suppress("DEPRECATION")
            setOnSystemUiVisibilityChangeListener { requestApplyInsets() }
            addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
                root.requestApplyInsets()
            }
        }
        val probeParams = WindowManager.LayoutParams(
            1,
            1,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        if (runCatching { wm.addView(probe, probeParams) }.isSuccess) {
            fullscreenProbe = probe
        } else {
            // An unknown reading must never suppress the island.
            otherAppFullscreen.value = false
        }
    }

    private fun stopFullscreenProbe() {
        val probe = fullscreenProbe
        fullscreenProbe = null
        fullscreenCandidate = false
        sawVisibleStatusBar = false
        fullscreenConfirmation?.let(mainHandler::removeCallbacks)
        fullscreenConfirmation = null
        otherAppFullscreen.value = false
        if (probe != null) runCatching { wm.removeView(probe) }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!IslandSettings.isOn(Trigger.Charging)) return
            val connected = when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            IslandController.flash(IslandMode.Charging(pct, connected), 3500)
        }
    }

    /**
     * Low battery: warns once at 20% and once at 10%, only while not charging.
     * If the phone is already low when the island starts, nothing pops up (that is not a change).
     */
    private var batteryPrimed = false
    private var lowWarned = 100   // lowest step already shown (20 or 10), 100 = none

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged || pct > 20) {
                lowWarned = 100
                batteryPrimed = true
                return
            }
            val step = if (pct <= 10) 10 else 20
            if (!batteryPrimed) {
                batteryPrimed = true
                lowWarned = step
                return
            }
            if (step < lowWarned) {
                lowWarned = step
                if (IslandSettings.isOn(Trigger.Battery)) {
                    IslandController.flash(IslandMode.LowBattery(pct), 5000)
                }
            }
        }
    }

    /** Silent / vibrate (ringer) and Do Not Disturb. Both are readable without any permission. */
    private var lastRinger = -1
    private var lastFilter = -1

    private val soundReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!IslandSettings.isOn(Trigger.Toggles)) return
            when (i.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = c.getSystemService(AudioManager::class.java)?.ringerMode ?: return
                    if (mode == lastRinger) return
                    lastRinger = mode
                    val m = when (mode) {
                        AudioManager.RINGER_MODE_SILENT -> IslandMode.Toggle(ToggleKind.Silent, true)
                        AudioManager.RINGER_MODE_VIBRATE -> IslandMode.Toggle(ToggleKind.Vibrate, true)
                        else -> IslandMode.Toggle(ToggleKind.Silent, false)
                    }
                    IslandController.flash(m, 2500)
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val f = c.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: return
                    if (f == lastFilter || f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
                    lastFilter = f
                    val on = f != NotificationManager.INTERRUPTION_FILTER_ALL
                    IslandController.flash(IslandMode.Toggle(ToggleKind.Dnd, on), 2500)
                }
            }
        }
    }

    /** Flashlight. The first report per camera is the current state, not a change, so it is skipped. */
    private val torchStates = HashMap<String, Boolean>()
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            val prev = torchStates.put(cameraId, enabled)
            if (prev == null || prev == enabled) return
            if (IslandSettings.isOn(Trigger.Toggles)) {
                IslandController.flash(IslandMode.Toggle(ToggleKind.Flashlight, enabled), 2000)
            }
        }
    }

    /** Bluetooth connect + wired headphones. Reading the name needs BLUETOOTH_CONNECT (Android 12+). */
    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!IslandSettings.isOn(Trigger.Devices)) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val name = runCatching { dev?.name }.getOrNull()
                        ?: AppLanguage.wrap(c).getString(R.string.island_bluetooth_device)
                    val headphones = runCatching {
                        dev?.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
                    }.getOrDefault(false)
                    IslandController.flash(IslandMode.Device(name, headphones), 3500)
                }
                Intent.ACTION_HEADSET_PLUG -> if (i.getIntExtra("state", 0) == 1) {
                    IslandController.flash(
                        IslandMode.Device(
                            i.getStringExtra("name")
                                ?: AppLanguage.wrap(c).getString(R.string.island_wired_headphones),
                            true
                        ),
                        3000
                    )
                }
            }
        }
    }

    fun show() {
        if (view != null) return

        val o = OverlayOwner().also { it.create() }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Position is computed by us in applyPos() (not by Android's CENTER_HORIZONTAL),
            // so system transitions (app open, call screen, lock) can never leave it off-center.
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = (IslandSettings.offsetY.value * density).toInt()
            // Let the window extend into the camera cutout area
            // The island sits at the top and the keyboard at the bottom, so never resize / pan for the IME
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Services keep the system locale even when the user picked a language inside the app.
        // Build the overlay with the saved locale so its own labels are translated as well.
        val v = ComposeView(AppLanguage.wrap(host)).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent {
                IslandTheme {
                    val mode by IslandController.mode.collectAsState()
                    val level by IslandController.level.collectAsState()
                    val scale by IslandSettings.scale.collectAsState()
                    val widthScale by IslandSettings.widthScale.collectAsState()
                    val heightScale by IslandSettings.heightScale.collectAsState()
                    val corner by IslandSettings.corner.collectAsState()
                    val speed by IslandSettings.animSpeed.collectAsState()
                    val glow by IslandSettings.glow.collectAsState()
                    val haptics by IslandSettings.haptics.collectAsState()
                    val useAppAccent by IslandSettings.islandAppAccent.collectAsState()
                    val replying by IslandController.replying.collectAsState()
                    val queued by IslandController.queued.collectAsState()
                    val secondary by IslandController.secondary.collectAsState()
                    val idlePill by IslandSettings.idlePill.collectAsState()
                    val fullscreen by otherAppFullscreen.collectAsState()
                    val fullscreenBehavior by IslandSettings.fullscreenBehavior.collectAsState()
                    val allowedInFullscreen = !fullscreen || when (fullscreenBehavior) {
                        FullscreenBehavior.ShowAll -> true
                        FullscreenBehavior.HideIdle -> mode !is IslandMode.Idle
                        FullscreenBehavior.AlertsOnly ->
                            mode is IslandMode.Notification || mode is IslandMode.Call
                    }
                    // Nothing happening -> the island is not on screen at all.
                    // It grows out of the top when an activity starts and shrinks away when it ends.
                    val last = remember { LastMode() }
                    if (mode !is IslandMode.Idle) last.value = mode
                    // Keep the old mode only for the short exit animation. Album art can be several
                    // megabytes, so retaining the last Media mode forever wastes RAM while idle.
                    LaunchedEffect(mode) {
                        if (mode is IslandMode.Idle) {
                            delay(250)
                            last.value = IslandMode.Idle
                        }
                    }

                    AnimatedVisibility(
                        // Full-screen suppression keeps alerts event-driven: a notification or call
                        // appears immediately, then the underlying mode is hidden again when it ends.
                        visible = allowedInFullscreen && (mode !is IslandMode.Idle || idlePill),
                        // Fast + snappy: no slow spring, appears almost instantly
                        enter = scaleIn(tween(140), 0.6f, TransformOrigin(0.5f, 0f)) + fadeIn(tween(90)),
                        exit = scaleOut(tween(160), 0.6f, TransformOrigin(0.5f, 0f)) + fadeOut(tween(140)),
                    ) {
                    DynamicIslandUi(
                        mode = if (mode is IslandMode.Idle && idlePill) mode else last.value,
                        level = level,
                        scale = scale,
                        widthScale = widthScale,
                        heightScale = heightScale,
                        cornerFactor = corner,
                        animSpeed = speed,
                        glow = glow,
                        haptics = haptics,
                        onClick = IslandController::toggle,
                        onLongPress = IslandController::large,
                        onDismiss = IslandController::dismiss,
                        onPrev = IslandController::prev,
                        onPlayPause = IslandController::playPause,
                        onNext = IslandController::next,
                        onSeek = IslandController::seekTo,
                        onAnswer = IslandController::answerCall,
                        onDecline = IslandController::declineCall,
                        onMarkRead = IslandController::markRead,
                        onReply = IslandController::startReply,
                        onSendReply = IslandController::sendReply,
                        onCancelReply = IslandController::cancelReply,
                        replying = replying,
                        onOpenMedia = IslandController::openMedia,
                        secondary = secondary,
                        queued = queued,
                        onSwipeSide = IslandController::skip,
                        onSwipe = IslandController::swipe,
                        appAccent = if (useAppAccent) MaterialTheme.colorScheme.primary else null,
                    )
                    }
                }
            }
        }

        // Tap outside the island while typing = cancel the reply (needs FLAG_WATCH_OUTSIDE_TOUCH, set below)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) IslandController.cancelReply()
            false
        }

        // Whenever the window size changes (pill grows / shrinks), re-center it ourselves.
        v.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) v.post { applyPos() }
        }
        // Insets change on rotation/fold state changes even if the pill keeps the same size.
        v.setOnApplyWindowInsetsListener { _, insets ->
            v.post { applyPos() }
            insets
        }

        val added = runCatching { wm.addView(v, lp) }
        if (added.isFailure) {
            // e.g. overlay permission revoked. Leave everything clean so show() can be retried.
            // A ComposeView that never attached does not run its normal detach disposal strategy.
            v.disposeComposition()
            s.cancel()
            o.destroy()
            return
        }

        owner = o
        scope = s
        params = lp
        view = v
        startFullscreenProbe()
        registerReceivers()

        // Replying needs the keyboard: make the window focusable only while the user is typing
        s.launch {
            IslandController.replying.collect { typing ->
                val p = params ?: return@collect
                val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                val w = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                p.flags = if (typing) (p.flags and f.inv()) or w else (p.flags or f) and w.inv()
                view?.let { runCatching { wm.updateViewLayout(it, p) } }
            }
        }

        // Sliders and camera alignment move the island live.
        s.launch {
            combine(
                IslandSettings.offsetX,
                IslandSettings.offsetY,
                IslandSettings.autoAlignCamera,
            ) { _, _, _ -> Unit }.collect { applyPos() }
        }

        // Rebuild the ComposeView when the in-app language changes; service contexts are otherwise
        // unaffected by Activity.recreate(). The post survives cancellation of this overlay scope.
        s.launch {
            AppLanguage.changes.drop(1).collect {
                v.post {
                    if (view === v) {
                        hide()
                        show()
                    }
                }
            }
        }
    }

    private fun screenW(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else host.resources.displayMetrics.widthPixels

    private fun screenH(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
        else host.resources.displayMetrics.heightPixels

    /** The top display cutout is normally the front camera/notch; ignore bottom/hinge cutouts. */
    private fun cameraCutout(v: ComposeView): android.graphics.Rect? {
        if (Build.VERSION.SDK_INT < 28) return null
        val cutout = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.displayCutout
        } else {
            v.rootWindowInsets?.displayCutout
        } ?: return null
        val topThird = screenH() / 3
        return cutout.boundingRects
            .asSequence()
            .filter { !it.isEmpty && it.centerY() <= topThird }
            .minByOrNull { it.top }
    }

    /** Center on the front camera when available, then apply the user's fine-tuning offsets. */
    private fun applyPos() {
        val v = view ?: return
        val p = params ?: return
        if (v.width <= 0) return
        val auto = IslandSettings.autoAlignCamera.value
        val cutout = if (auto) cameraCutout(v) else null
        val baseCenterX = cutout?.centerX() ?: screenW() / 2
        val baseY = cutout?.let { (it.centerY() - v.height / 2).coerceAtLeast(0) } ?: 0
        val nx = baseCenterX - v.width / 2 + (IslandSettings.offsetX.value * density).toInt()
        val ny = (if (auto) baseY else 0) + (IslandSettings.offsetY.value * density).toInt()
        if (p.x != nx || p.y != ny) {
            p.x = nx
            p.y = ny
            runCatching { wm.updateViewLayout(v, p) }
        }
    }

    fun hide() {
        val v = view ?: return
        // Clear our strong references first so callbacks racing with teardown become no-ops.
        view = null
        params = null
        stopFullscreenProbe()
        unregisterReceivers()
        scope?.cancel()
        scope = null
        runCatching { wm.removeView(v) }
        // Dispose explicitly as well: removeView can fail when a permission/host disappears.
        runCatching { v.disposeComposition() }
        owner?.destroy()
        owner = null
    }

    private fun registerReceivers() {
        if (receiversOn) return
        receiversOn = true
        ContextCompat.registerReceiver(
            host, powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            host, deviceReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(Intent.ACTION_HEADSET_PLUG)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Low battery
        batteryPrimed = false
        lowWarned = 100
        ContextCompat.registerReceiver(
            host, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Silent / DND: remember the current state first, so only real changes show up
        lastRinger = runCatching { host.getSystemService(AudioManager::class.java).ringerMode }.getOrDefault(-1)
        lastFilter = runCatching {
            host.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        }.getOrDefault(-1)
        ContextCompat.registerReceiver(
            host, soundReceiver,
            IntentFilter().apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Flashlight
        torchStates.clear()
        runCatching {
            host.getSystemService(CameraManager::class.java)
                .registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
    }

    private fun unregisterReceivers() {
        if (!receiversOn) return
        receiversOn = false
        runCatching { host.unregisterReceiver(powerReceiver) }
        runCatching { host.unregisterReceiver(deviceReceiver) }
        runCatching { host.unregisterReceiver(batteryReceiver) }
        runCatching { host.unregisterReceiver(soundReceiver) }
        runCatching { host.getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        torchStates.clear()
    }
}

/** Gives Compose (running inside a service window) a lifecycle / saved-state / view-model owner. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry

    fun create() {
        ssc.performAttach()
        ssc.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}

/** Remembers the last real activity so the island can still draw it while it animates away. */
private class LastMode {
    var value: IslandMode = IslandMode.Idle
}
