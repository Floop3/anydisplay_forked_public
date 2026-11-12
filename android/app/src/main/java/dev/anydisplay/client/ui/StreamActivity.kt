package dev.anydisplay.client.ui

import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.anydisplay.client.Elog
import dev.anydisplay.client.R
import dev.anydisplay.client.data.Settings
import dev.anydisplay.client.data.StreamOrientation
import dev.anydisplay.client.data.TouchMode
import dev.anydisplay.client.data.Upscaler
import dev.anydisplay.client.input.HidKeys
import dev.anydisplay.client.input.InputRouter
import dev.anydisplay.client.protocol.Bodies
import dev.anydisplay.client.protocol.KeyMods
import dev.anydisplay.client.protocol.Proto
import dev.anydisplay.client.protocol.StreamConfig
import dev.anydisplay.client.session.Phase
import dev.anydisplay.client.session.Session
import dev.anydisplay.client.session.Status
import dev.anydisplay.client.video.GpuUpscaler
import dev.anydisplay.client.video.VideoDecoder

/** Full-screen immersive display: decodes the stream onto a SurfaceView and sends touch, pen and keyboard input. */
class StreamActivity : BaseActivity(), SurfaceHolder.Callback, VideoDecoder.Listener {
    private lateinit var settings: Settings
    private lateinit var layout: VideoLayout
    private lateinit var cursor: CursorOverlay
    private lateinit var stats: TextView
    private lateinit var waiting: TextView
    private lateinit var menu: FloatingMenu
    private lateinit var keyboard: KeyboardSink
    private lateinit var router: InputRouter
    private var session: Session? = null
    private var decoder: VideoDecoder? = null
    private var upscaler: GpuUpscaler? = null
    private var windowSurface: Surface? = null
    private var streamConfig: StreamConfig? = null
    private var outputPath = PATH_NONE
    private var outputSrcW = 0
    private var outputSrcH = 0
    private var surfaceW = 0
    private var surfaceH = 0
    private var upscalerUnsupported: Upscaler? = null
    private var upscalerRetryAfter = 0L
    private var lastUpscalerMode: Upscaler? = null
    private var startingOutput = false
    private val ui = Handler(Looper.getMainLooper())
    private var lastReleased = 0L
    private var lastBytes = 0L
    private var lastStatsAt = 0L
    private var keyboardShown = false
    private lateinit var clipboard: ClipboardManager

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { pushClipboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = dev.anydisplay.client.session.SessionHub.session
        if (s == null || !s.connected) {
            finish()
            return
        }
        session = s
        settings = Settings(this)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        requestedOrientation = when (settings.orientation) {
            StreamOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            StreamOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            StreamOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        layout = VideoLayout(this)
        cursor = CursorOverlay(this)
        stats = TextView(this).apply {
            setBackgroundResource(R.drawable.bg_overlay)
            setTextColor(0xFFE8F5E9.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            visibility = if (settings.statsOverlay) View.VISIBLE else View.GONE
        }
        waiting = TextView(this).apply {
            setText(R.string.waiting_for_video)
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        keyboard = KeyboardSink(this, object : KeyboardSink.Output {
            override fun text(s: String) = session?.sendInput(Proto.TEXT, Bodies.text(s)) ?: Unit
            override fun tap(usage: Int) = tapKey(usage, 0)
        })
        menu = FloatingMenu(this) { x, y -> settings.menuPosition = x to y }
        val dp = resources.displayMetrics.density
        layout.addView(cursor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layout.addView(waiting, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layout.addView(stats, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins((12 * dp).toInt(), (12 * dp).toInt(), 0, 0)
        })
        layout.addView(keyboard, FrameLayout.LayoutParams(1, 1))
        layout.addView(menu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(layout)

        router = InputRouter(layout, layout.videoRect, { type, body -> session?.sendInput(type, body) },
            ViewConfiguration.get(this).scaledTouchSlop.toFloat()).apply {
            mode = settings.touchMode
            palmRejection = settings.palmRejection
        }
        layout.touchHandler = router::onTouchEvent
        layout.genericHandler = router::onGenericMotionEvent
        layout.onRectChanged = { r ->
            cursor.videoRect = r
            router.onGeometryChanged()
            cursor.invalidate()
            // The displayed size changed (rotation, window size, stream size): the path may no longer fit.
            ensureOutput()
        }
        cursor.onPosition = { x, y -> router.syncCursor(x, y) }
        layout.surfaceView.holder.addCallback(this)
        buildMenu()
        placeMenu()
        s.config?.let { applyConfig(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@StreamActivity, R.string.still_connected, Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        hideSystemBars()
    }

    private fun placeMenu() {
        layout.post {
            val pos = settings.menuPosition
            if (pos != null) {
                menu.translationX = pos.first
                menu.translationY = pos.second
            } else {
                menu.translationX = (layout.width - menu.width - 16 * resources.displayMetrics.density)
                menu.translationY = (layout.height - menu.height) / 3f
            }
            menu.clampIntoParent()
        }
    }

    private fun modeIcon(m: TouchMode) = when (m) {
        TouchMode.DIRECT -> R.drawable.ic_touch
        TouchMode.TRACKPAD -> R.drawable.ic_mouse
        TouchMode.PEN_ONLY -> R.drawable.ic_pen
    }

    private fun modeLabel(m: TouchMode) = getString(when (m) {
        TouchMode.DIRECT -> R.string.touch_mode_direct
        TouchMode.TRACKPAD -> R.string.touch_mode_trackpad
        TouchMode.PEN_ONLY -> R.string.touch_mode_pen
    })

    private fun buildMenu() {
        menu.setItems(listOf(
            FloatingMenu.Item("mode", modeIcon(router.mode), getString(R.string.menu_touch_mode)) {
                router.mode = router.mode.next()
                menu.setIcon("mode", modeIcon(router.mode), modeLabel(router.mode))
                Snackbar.make(layout, modeLabel(router.mode), Snackbar.LENGTH_SHORT).show()
            },
            FloatingMenu.Item("keyboard", R.drawable.ic_keyboard, getString(R.string.menu_keyboard)) { toggleKeyboard() },
            FloatingMenu.Item("undo", R.drawable.ic_undo, getString(R.string.menu_undo)) { shortcut(HidKeys.letter('z'), 0) },
            FloatingMenu.Item("redo", R.drawable.ic_redo, getString(R.string.menu_redo)) {
                if (session?.hostIsMac == true) shortcut(HidKeys.letter('z'), KeyMods.SHIFT) else shortcut(HidKeys.letter('y'), 0)
            },
            FloatingMenu.Item("copy", R.drawable.ic_copy, getString(R.string.menu_copy)) { shortcut(HidKeys.letter('c'), 0) },
            FloatingMenu.Item("paste", R.drawable.ic_paste, getString(R.string.menu_paste)) { shortcut(HidKeys.letter('v'), 0) },
            FloatingMenu.Item("esc", 0, getString(R.string.menu_escape), text = "Esc") { tapKey(HidKeys.ESCAPE, 0) },
            FloatingMenu.Item("disconnect", R.drawable.ic_power, getString(R.string.disconnect)) { confirmDisconnect() },
        ))
        menu.setIcon("mode", modeIcon(router.mode), modeLabel(router.mode))
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.disconnect_title, session?.hostName ?: ""))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disconnect) { _, _ -> session?.disconnect() }
            .show()
    }

    // ------------------------------------------------------------ keyboard

    /** Command (macOS) or Ctrl (Windows) + key, with the modifier sent as its own key like a real keyboard. */
    private fun shortcut(usage: Int, extraMods: Int) {
        val mac = session?.hostIsMac == true
        val primary = if (mac) KeyMods.META else KeyMods.CTRL
        val primaryUsage = if (mac) 0xE3 else 0xE0
        val mods = primary or extraMods
        val s = session ?: return
        s.sendInput(Proto.KEY, Bodies.key(true, primaryUsage, primary))
        if (extraMods and KeyMods.SHIFT != 0) s.sendInput(Proto.KEY, Bodies.key(true, 0xE1, mods))
        s.sendInput(Proto.KEY, Bodies.key(true, usage, mods))
        s.sendInput(Proto.KEY, Bodies.key(false, usage, mods))
        if (extraMods and KeyMods.SHIFT != 0) s.sendInput(Proto.KEY, Bodies.key(false, 0xE1, primary))
        s.sendInput(Proto.KEY, Bodies.key(false, primaryUsage, 0))
    }

    private fun tapKey(usage: Int, mods: Int) {
        session?.sendInput(Proto.KEY, Bodies.key(true, usage, mods))
        session?.sendInput(Proto.KEY, Bodies.key(false, usage, mods))
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (keyboardShown) {
            imm.hideSoftInputFromWindow(keyboard.windowToken, 0)
            keyboard.clearFocus()
            keyboardShown = false
        } else {
            keyboard.requestFocus()
            imm.restartInput(keyboard)
            imm.showSoftInput(keyboard, InputMethodManager.SHOW_IMPLICIT)
            keyboardShown = true
        }
    }

    @Suppress("DEPRECATION") // ACTION_MULTIPLE still carries committed text from some IMEs and keyboards.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME ||
            code == KeyEvent.KEYCODE_APP_SWITCH
        ) return super.dispatchKeyEvent(event)
        val s = session ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            event.characters?.let { s.sendInput(Proto.TEXT, Bodies.text(it)) }
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        val usage = HidKeys.usage(code)
        if (usage != 0) {
            s.sendInput(Proto.KEY, Bodies.key(down, usage, HidKeys.modifiers(event.metaState)))
            return true
        }
        val ch = event.unicodeChar
        if (ch != 0) {
            if (down) s.sendInput(Proto.TEXT, Bodies.text(String(Character.toChars(ch))))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ clipboard

    private fun pushClipboard() {
        val text = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) {
            null
        } ?: return
        session?.sendClipboard(text)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            clipboard.addPrimaryClipChangedListener(clipListener)
        } else {
            clipboard.removePrimaryClipChangedListener(clipListener)
            router.cancelFingers()
        }
    }

    // ------------------------------------------------------------ video

    private fun applyConfig(cfg: StreamConfig) {
        streamConfig = cfg
        layout.setStreamSize(cfg.width, cfg.height)
        // The GL path sizes the surface to the video rectangle; the direct path keeps the stream size.
        if (outputPath != PATH_GPU) setSurfaceSize(cfg.width, cfg.height)
        router.streamWidth = cfg.width
        router.streamHeight = cfg.height
        router.onGeometryChanged()
        cursor.streamWidth = cfg.width
        cursor.streamHeight = cfg.height
        if (!cfg.clientCursor) cursor.clear()
        // A new granted config is a meaningful retry point for a transient upscaler failure.
        upscalerRetryAfter = 0
        ensureOutput()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val changed = windowSurface !== holder.surface
        windowSurface = holder.surface
        if (changed) {
            // A new Surface means a new EGL window target; drop the old decoder/EGL surface first.
            releaseOutput()
            upscalerRetryAfter = 0
        }
        ensureOutput()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // SurfaceView can resize its BufferQueue underneath an EGL window surface; restart on a real change.
        if (width != surfaceW || height != surfaceH) {
            upscalerRetryAfter = 0
            releaseOutput()
            ensureOutput()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        windowSurface = null
        releaseOutput()
    }

    /**
     * Chooses between the direct MediaCodec -> SurfaceView path and the GL upscaler from the granted stream size
     * and the on-screen video rectangle, then starts or switches the output. Runs on the main thread.
     */
    private fun ensureOutput() {
        if (startingOutput) return
        val cfg = streamConfig ?: return
        val window = windowSurface ?: return
        val rect = layout.videoRect
        if (rect.width() < 1f || rect.height() < 1f) return
        if (!window.isValid) return
        val mode = settings.upscaler
        if (mode != lastUpscalerMode) {
            // A different user choice is a fresh attempt even if the previous one was unsupported.
            lastUpscalerMode = mode
            upscalerUnsupported = null
            upscalerRetryAfter = 0
        }
        val gpu = gpuWanted(cfg, rect)
        if (outputPath != PATH_NONE) {
            if (outputPath == PATH_GPU) {
                if (gpu && outputSrcW == cfg.width && outputSrcH == cfg.height &&
                    Math.round(rect.width()) == surfaceW && Math.round(rect.height()) == surfaceH
                ) return
            } else if (!gpu && outputSrcW == cfg.width && outputSrcH == cfg.height) {
                return
            }
            releaseOutput()
        }
        startingOutput = true
        try {
            startOutput(cfg, window, rect, gpu)
        } finally {
            startingOutput = false
        }
    }

    /** True when the granted stream is smaller than the pixels of the video rectangle, i.e. when enlargement is needed. */
    private fun gpuWanted(cfg: StreamConfig, rect: RectF): Boolean {
        val mode = settings.upscaler
        if (mode == Upscaler.CURRENT) return false
        if (upscalerUnsupported == mode) return false
        // Transient surface/EGL failures retry only after a short backoff or a new surface/config.
        if (SystemClock.uptimeMillis() < upscalerRetryAfter) return false
        if (cfg.width <= 0 || cfg.height <= 0) return false
        val sx = rect.width() / cfg.width
        val sy = rect.height() / cfg.height
        return minOf(sx, sy) > 1.02f
    }

    private fun startOutput(cfg: StreamConfig, window: Surface, rect: RectF, gpu: Boolean) {
        if (gpu) {
            val w = Math.max(1, Math.round(rect.width()))
            val h = Math.max(1, Math.round(rect.height()))
            val renderer = GpuUpscaler(settings.upscaler, settings.fsrSharpness, cfg.width, cfg.height) { message, permanent ->
                ui.post { onUpscalerFailed(message, permanent) }
            }
            setSurfaceSize(w, h)
            val started = renderer.start(window)
            val input = renderer.inputSurface
            if (started && input != null) {
                upscaler = renderer
                outputPath = PATH_GPU
                outputSrcW = cfg.width
                outputSrcH = cfg.height
                Elog.i("upscaler " + settings.upscaler.key + " " + cfg.width + "x" + cfg.height + " -> " + w + "x" + h)
                attachDecoder(input)
                return
            }
            val permanent = renderer.lastFailurePermanent
            renderer.release()
            noteUpscalerFailure(permanent)
            Elog.w("upscaler " + settings.upscaler.key + " start failed (" +
                (if (permanent) "unsupported" else "transient") + "); using the direct path")
            Snackbar.make(layout, R.string.upscaler_failed, Snackbar.LENGTH_SHORT).show()
        }
        setSurfaceSize(cfg.width, cfg.height)
        outputPath = PATH_DIRECT
        outputSrcW = cfg.width
        outputSrcH = cfg.height
        attachDecoder(window)
    }

    private fun attachDecoder(surface: Surface) {
        val d = VideoDecoder(surface, this)
        decoder = d
        session?.attachVideo(d)
    }

    private fun releaseOutput() {
        val d = decoder
        decoder = null
        if (d != null) {
            session?.detachVideo(d)
            d.release()
        }
        val g = upscaler
        upscaler = null
        g?.release()
        outputPath = PATH_NONE
        outputSrcW = 0
        outputSrcH = 0
        // Force the next path to apply its surface size even if the numbers happen to match.
        surfaceW = 0
        surfaceH = 0
    }

    private fun onUpscalerFailed(message: String, permanent: Boolean) {
        if (outputPath != PATH_GPU) return
        Elog.w("upscaler " + settings.upscaler.key + " failed: " + message)
        noteUpscalerFailure(permanent)
        releaseOutput()
        ensureOutput()
        Snackbar.make(layout, R.string.upscaler_failed, Snackbar.LENGTH_SHORT).show()
    }

    /** Permanent causes latch the mode until the user picks another one; transient ones retry after a backoff. */
    private fun noteUpscalerFailure(permanent: Boolean) {
        if (permanent) upscalerUnsupported = settings.upscaler
        else upscalerRetryAfter = SystemClock.uptimeMillis() + 3000
    }

    private fun setSurfaceSize(w: Int, h: Int) {
        if (w == surfaceW && h == surfaceH) return
        surfaceW = w
        surfaceH = h
        layout.surfaceView.holder.setFixedSize(w, h)
    }

    override fun onAck(streamId: Long, frameId: Long, decodeUs: Long, released: Boolean, hostPtsUs: Long) {
        session?.ack(streamId, frameId, decodeUs, released, hostPtsUs)
        if (released && waiting.visibility == View.VISIBLE) ui.post { waiting.visibility = View.GONE }
    }

    override fun onKeyframeNeeded() {
        session?.requestKeyframe()
    }

    override fun onDecoderStarted(name: String, config: StreamConfig) {
        ui.post { applyConfig(config) }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onStatus(status: Status) {
        if (status.phase != Phase.CONNECTED || dev.anydisplay.client.session.SessionHub.session !== session) {
            Toast.makeText(this, status.lastError?.let { getString(R.string.disconnected) + ": " + it }
                ?: getString(R.string.disconnected), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        status.config?.let { applyConfig(it) }
    }

    override fun onResume() {
        super.onResume()
        session?.attachCursor(cursor)
        ui.post(statsTick)
    }

    override fun onPause() {
        ui.removeCallbacks(statsTick)
        session?.attachCursor(null)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settings.orientation == StreamOrientation.AUTO) session?.refreshConfigRequest()
        placeMenu()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            ui.postDelayed(this, 500)
        }
    }

    private fun updateStats() {
        val s = session ?: return
        if (!settings.statsOverlay) return
        val now = System.nanoTime()
        val released = s.totalReleased.get()
        val bytes = s.bytesSoFar()
        val dt = (now - lastStatsAt) / 1e9
        if (lastStatsAt != 0L && dt > 0) {
            val fps = (released - lastReleased) / dt
            val cfg = s.config
            val lat = s.lastLatencyUs
            stats.text = buildString {
                append(getString(R.string.stats_decode_fps, fps))
                if (cfg != null) append("  ${cfg.width}×${cfg.height} ${Proto.codecName(cfg.codec)}")
                append("\n" + getString(R.string.stats_decode_rtt, s.lastAvgDecodeUs / 1000.0, s.rttUs / 1000.0))
                if (lat >= 0) append("\n" + getString(R.string.stats_latency, lat / 1000.0))
                append("\n" + getString(R.string.stats_throughput, (bytes - lastBytes) * 8 / dt / 1e6, s.totalDropped.get()))
                decoder?.decoderName?.takeIf { it.isNotEmpty() }?.let { append("\n$it") }
            }
        }
        lastStatsAt = now
        lastReleased = released
        lastBytes = bytes
    }

    override fun onDestroy() {
        releaseOutput()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
        Elog.d("stream activity destroyed")
    }

    private companion object {
        const val PATH_NONE = 0
        const val PATH_DIRECT = 1
        const val PATH_GPU = 2
    }
}
