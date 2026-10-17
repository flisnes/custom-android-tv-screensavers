package com.fleece.screensaver

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs one scene full screen, exactly as the dream would (same renderer, same dimming), until Back is
 * pressed. An app cannot start the system dream itself, so this is how scenes are previewed.
 *
 * OK opens the tweak panel: Up/Down picks a value, Left/Right changes it (live, saved at once to
 * [TweakStore], used by the dream from its next start), OK resets the value, Back closes the panel.
 * Up (with the panel closed) toggles an info overlay with the frame rate and the scene's own readout.
 */
class PreviewActivity : Activity() {

    private lateinit var scene: SceneInfo
    private lateinit var store: TweakStore
    private lateinit var tweaks: Tweaks
    private lateinit var stage: FrameLayout
    private var glView: GLSurfaceView? = null
    private var renderer: CountingRenderer? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var hud: TextView
    private var hudOn = false
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var help: TextView
    private val rows = mutableListOf<Pair<View, List<TextView>>>()   // one per param, then "Reset all"
    private var sel = 0
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = SceneCatalog.scenes.firstOrNull { it.key == intent.getStringExtra(EXTRA_SCENE) }
        if (s == null) {
            finish()
            return
        }
        scene = s
        store = TweakStore(this)
        tweaks = store.load(scene)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyBrightness()

        stage = FrameLayout(this)
        val label = TextView(this).apply {
            text = "${scene.title}  ·  OK: tweak  ·  Up: info  ·  Back: return"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFFCCCCCC.toInt())
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        hud = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFE0E0E0.toInt())
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0xA0000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        panel = buildPanel()

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(stage)
            addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins(48, 0, 0, 36) })
            addView(hud, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(20), 0, 0) })
            addView(panel, FrameLayout.LayoutParams(dp(360), MATCH_PARENT, Gravity.END))
        })
        startScene()
        // Hide the label after a few seconds so it does not sit on the panel.
        label.postDelayed({
            label.animate().alpha(0f).setDuration(800).withEndAction { label.visibility = View.GONE }
        }, 4000)
        hideSystemBars()
    }

    // ---- scene ----------------------------------------------------------------------------------

    private fun startScene() {
        glView?.let { it.onPause(); stage.removeView(it) }
        val r = CountingRenderer(scene.make(this, tweaks))
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(scene.glVersion)
            setRenderer(r)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        renderer = r
        glView = view
        stage.addView(view)
        if (resumed) view.onResume()
    }

    private val restartScene = Runnable { startScene() }

    private fun applyBrightness() {
        // Same dimming as the dream, so the preview looks like the real thing.
        window.attributes = window.attributes.apply { screenBrightness = tweaks["panelBrightness"] }
    }

    /** Counts frames for the info overlay; otherwise passes straight through. */
    private class CountingRenderer(val inner: GLSurfaceView.Renderer) : GLSurfaceView.Renderer {
        val frames = AtomicInteger()
        val worstNanos = AtomicLong()
        private var last = 0L
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { last = 0L; inner.onSurfaceCreated(gl, config) }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = inner.onSurfaceChanged(gl, width, height)
        override fun onDrawFrame(gl: GL10?) {
            inner.onDrawFrame(gl)
            val now = System.nanoTime()
            if (last != 0L) {
                frames.incrementAndGet()
                val d = now - last
                if (d > worstNanos.get()) worstNanos.set(d)
            }
            last = now
        }
    }

    // ---- info overlay -----------------------------------------------------------------------------

    private var hudLastNanos = 0L
    private val hudTick = object : Runnable {
        override fun run() {
            val r = renderer
            val now = System.nanoTime()
            if (r != null && hudLastNanos != 0L) {
                val secs = (now - hudLastNanos) / 1e9
                val fps = r.frames.getAndSet(0) / secs
                val worstMs = r.worstNanos.getAndSet(0) / 1e6
                val extra = (r.inner as? SceneStatus)?.status()?.let { "\n$it" } ?: ""
                hud.text = "%.1f fps · slowest frame %.0f ms".format(fps, worstMs) + extra
            }
            hudLastNanos = now
            handler.postDelayed(this, 500)
        }
    }

    private fun updateHudVisibility() {
        val show = hudOn || panel.visibility == View.VISIBLE
        hud.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hudTick)
        if (show) {
            hudLastNanos = 0L
            renderer?.frames?.set(0); renderer?.worstNanos?.set(0)
            hud.text = "measuring…"
            handler.post(hudTick)
        }
    }

    // ---- tweak panel ------------------------------------------------------------------------------

    private fun buildPanel(): LinearLayout {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var group = ""
        scene.params.forEach { p ->
            if (p.group != group) {
                group = p.group
                list.addView(text(group.uppercase(), 12f, 0xFF8A8A8A.toInt(), bold = true).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(2))
                })
            }
            val name = text(p.label, 14f, Color.WHITE)
            val value = text("", 14f, Color.WHITE).apply { gravity = Gravity.END }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(5), dp(12), dp(5))
                addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(12) })
            }
            list.addView(row)
            rows += row to listOf(name, value)
        }
        val reset = text("Reset all to defaults", 14f, Color.WHITE)
        val resetRow = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(reset)
        }
        list.addView(resetRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        rows += resetRow to listOf(reset)

        scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        help = text("", 13f, 0xFFBBBBBB.toInt()).apply { setPadding(dp(12), dp(10), dp(12), dp(4)) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6101010.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(16))
            visibility = View.GONE
            addView(text(scene.title, 20f, Color.WHITE, bold = true).apply { setPadding(dp(12), 0, dp(12), 0) })
            addView(text("Up/Down choose · Left/Right change · OK reset value · Back close\nChanges are saved and used by the screensaver.",
                12f, 0xFF9A9A9A.toInt()).apply { setPadding(dp(12), dp(2), dp(12), dp(6)) })
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(help)
        }
    }

    private fun refreshPanel() {
        rows.forEachIndexed { i, (row, texts) ->
            row.setBackgroundColor(if (i == sel) 0xFF3A3A3A.toInt() else Color.TRANSPARENT)
            if (i < scene.params.size) {
                val p = scene.params[i]
                val v = tweaks[p.key]
                val changed = !p.isDefault(v)
                texts[1].text = if (!p.color) p.format(v) else SpannableString("■  " + p.format(v)).apply {
                    setSpan(ForegroundColorSpan(0xFF000000.toInt() or v.roundToInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(1.6f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                texts[1].setTextColor(if (changed) AMBER else Color.WHITE)
                texts[0].setTextColor(if (changed) AMBER else Color.WHITE)
            }
        }
        help.text = if (sel < scene.params.size) {
            val p = scene.params[sel]
            val range = if (p.color) "  (Left/Right: ${Palette.colors.size} colours)" else if (p.choices == null) "  (${p.format(p.min)} – ${p.format(p.max)})" else ""
            listOf(p.help, "Default: ${p.format(p.default)}$range" + if (p.restart) " · restarts the scene" else "")
                .filter { it.isNotEmpty() }.joinToString("\n")
        } else {
            "Changed now: ${tweaks.describeChanged()}"
        }
        val row = rows[sel].first
        scroll.post { scroll.smoothScrollTo(0, max(0, row.top - scroll.height / 3)) }
    }

    private fun showPanel(show: Boolean) {
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) refreshPanel()
        updateHudVisibility()
    }

    private fun change(p: Param, v: Float) {
        val old = tweaks[p.key]
        val nv = tweaks.set(p.key, v)
        if (nv == old) return
        store.save(scene, p.key, nv)
        if (p.key == "panelBrightness") applyBrightness()
        if (p.restart) {
            handler.removeCallbacks(restartScene)
            handler.postDelayed(restartScene, 1200)
        }
        refreshPanel()
    }

    private fun resetAll() {
        var restart = false
        scene.params.forEach { p ->
            if (!p.isDefault(tweaks[p.key]) && p.restart) restart = true
            tweaks.set(p.key, p.default)
        }
        store.reset(scene)
        applyBrightness()
        if (restart) { handler.removeCallbacks(restartScene); handler.post(restartScene) }
        refreshPanel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val open = panel.visibility == View.VISIBLE
        val isOk = event.keyCode in OK_KEYS
        if (!open) {
            when {
                isOk || event.keyCode == KeyEvent.KEYCODE_MENU -> { if (down && event.repeatCount == 0) showPanel(true); return true }
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_INFO -> {
                    if (down && event.repeatCount == 0) { hudOn = !hudOn; updateHudVisibility() }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) showPanel(false)
            return true
        }
        if (!down) return true
        val onParam = sel < scene.params.size
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { sel = (sel - 1 + rows.size) % rows.size; refreshPanel() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { sel = (sel + 1) % rows.size; refreshPanel() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (onParam) {
                val p = scene.params[sel]
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val fast = if (p.choices == null && event.repeatCount > 8) 5 else 1   // holding accelerates
                val v = tweaks[p.key]
                change(p, if (p.color) Palette.step(v.roundToInt(), dir).toFloat() else if (p.choices != null) (v + dir + p.choices.size) % p.choices.size else v + dir * p.step * fast)
            }
            else -> if (isOk && event.repeatCount == 0) {
                if (onParam) scene.params[sel].let { change(it, it.default) } else resetAll()
            }
        }
        return true
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        resumed = true
        glView?.onResume()
        if (hud.visibility == View.VISIBLE) updateHudVisibility()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacksAndMessages(null)
        glView?.onPause()
        super.onPause()
    }

    companion object {
        const val EXTRA_SCENE = "scene"
        private const val AMBER = 0xFFFFB74D.toInt()
        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
