package com.fleece.screensaver

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Topographic contour maps of the Moon (white lines on black), from the LRO LOLA height model in
 * assets/moon (see tools/moon_data.py). Three modes share one fragment shader (assets/moon/moon.frag,
 * also used verbatim by preview/moon.html — tune the numbers there and copy them here):
 *
 *  ZOOM  — a random window of the surface; a sweep rises from the lowest to the highest point and
 *          draws each contour as it passes, holds, fades out, and picks a new window.
 *  GLOBE — a slowly spinning orthographic globe with the same continuous sweep.
 *  LIT   — the spinning globe with a light behind the camera, off to one side: only the contour
 *          segments on slopes that face the light are drawn, like a hatched engraving.
 *
 * Requires a GL ES 3.0 context (R16F height textures, `#version 300 es`).
 */
class MoonRenderer(private val context: Context, private val mode: Mode) : GLSurfaceView.Renderer {

    enum class Mode(val id: Int) { ZOOM(0), GLOBE(1), LIT(2) }

    // ---- tunables (defaults mirror preview/moon.html) ------------------------------------------
    private val lineWidth = 1.4f
    private val majorWidth = 2.2f
    private val majorEvery = 5f
    private val minorBright = if (mode == Mode.LIT) 0.7f else 0.45f   // lit mode shows far fewer segments
    private val majorBright = if (mode == Mode.LIT) 1.0f else 0.8f
    private val glowBright = 0.9f
    private val glowLevels = 2.5f
    private val riseLevels = 1.5f           // each line fades in while the sweep travels this many levels past it
    private val denseMax = 0.35f
    private val sweepSec = 30f
    private val holdSec = 5f
    private val fadeSec = 2.5f
    private val ease = 0.6f

    // zoom
    private val autoDensity = 0.3f          // levels per screen px at the 80th-percentile slope
    private val minInterval = 50f
    private val windowBlur = 0.8f           // gaussian sigma in source px
    private val halfHeightMinDeg = 5f
    private val halfHeightMaxDeg = 9f
    private val latMaxDeg = 52f
    private val driftZoom = 0.08f
    private val driftPan = 0.03f
    private val pickBest = 3

    // globe / lit
    private val globeInterval = 1000f
    private val litInterval = 400f
    private val globeRadius = 0.86f
    private val revSec = if (mode == Mode.LIT) 420f else 360f
    private val tiltDeg = 18f
    private val tiltWobbleDeg = 12f
    private val wobbleSec = 600f
    private val lightAzDeg = -45f
    private val lightElDeg = 55f
    private val litSlope = 0.03f
    private val litSoft = 0.3f
    private val litAmbient = 0.04f
    private val litSweep = false

    // ---- GL state -------------------------------------------------------------------------------
    private var prog = 0
    private val u = HashMap<String, Int>()
    private var tex = 0
    private var texW = 0
    private var texH = 0
    private var vw = 1
    private var vh = 1
    private var maxTex = 4096

    // ---- data -----------------------------------------------------------------------------------
    private class Grid(val width: Int, val height: Int, val ppd: Float, val latMin: Float, val latMax: Float)
    private var scale = 0.5f
    private lateinit var globeGrid: Grid
    private lateinit var zoomGrid: Grid
    private var zoomData: ShortBuffer? = null           // memory-mapped 32 px/deg grid

    /** A prepared height field ready for upload, plus everything the sweep needs. */
    private class Field(
        val w: Int, val h: Int, var data: FloatArray?, val quant: FloatArray, val interval: Float,
        // zoom only
        val lat: Float = 0f, val lon: Float = 0f, val hh0: Float = 0f, val extract: FloatArray = FloatArray(4),
    )
    @Volatile private var pending: Field? = null
    @Volatile private var loading = false
    private var current: Field? = null

    // ---- animation --------------------------------------------------------------------------------
    private enum class Phase { SWEEP, HOLD, FADE }
    private var phase = Phase.SWEEP
    private var phaseT = 0f
    private var cycleT = 0f
    private var simT = 0f
    private var lastNanos = 0L
    private val rnd = java.util.Random()

    // =============================================================================================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        val mt = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, mt, 0); maxTex = mt[0]

        val fs = context.assets.open("moon/moon.frag").bufferedReader().readText()
        prog = link(VS, fs)
        for (name in UNIFORMS) u[name] = GLES30.glGetUniformLocation(prog, name)

        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tex = t[0]
        texW = 0; texH = 0

        readManifest()
        current = null; pending = null; loading = false
        phase = Phase.SWEEP; phaseT = 0f; cycleT = 0f; simT = 0f; lastNanos = 0L
        if (mode == Mode.ZOOM) {
            // Window extraction needs the viewport aspect; kicked off from onSurfaceChanged.
        } else {
            startLoad { loadGlobe() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        vw = width; vh = height
        if (mode == Mode.ZOOM && current == null && !loading) startLoad { pickWindow() }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else min((now - lastNanos) / 1e9f, 0.1f)
        lastNanos = now
        simT += dt

        // Swap in a freshly prepared field (first load, or the next zoom window after a fade).
        pending?.let { f ->
            if (current == null || phase == Phase.FADE && phaseT >= fadeSec) {
                upload(f); f.data = null; current = f; pending = null
                phase = Phase.SWEEP; phaseT = 0f; cycleT = 0f
            }
        }
        val cur = current
        if (cur == null) { GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }

        val sweepOn = mode != Mode.LIT || litSweep
        var sweepH = 1e9f
        var fade = min(1f, simT / 2f)
        var progress = 0f
        if (sweepOn) {
            phaseT += dt; cycleT += dt
            when (phase) {
                Phase.SWEEP -> if (phaseT >= sweepSec) { phase = Phase.HOLD; phaseT = 0f }
                Phase.HOLD -> if (phaseT >= holdSec) {
                    phase = Phase.FADE; phaseT = 0f
                    if (mode == Mode.ZOOM) startLoad { pickWindow() }
                }
                Phase.FADE -> if (phaseT >= fadeSec) {
                    if (mode != Mode.ZOOM) { phase = Phase.SWEEP; phaseT = 0f; cycleT = 0f }
                    else phaseT = fadeSec   // stay black until the next window is ready
                }
            }
            var p = if (phase == Phase.SWEEP) phaseT / sweepSec else 1f
            val e = p * p * (3f - 2f * p); p += (e - p) * ease
            sweepH = if (p >= 1f) 1e9f else quantile(cur.quant, p)
            fade = when (phase) {
                Phase.FADE -> max(0f, 1f - phaseT / fadeSec)
                Phase.SWEEP -> min(1f, phaseT / 1f)
                Phase.HOLD -> 1f
            }
            progress = min(cycleT / (sweepSec + holdSec + fadeSec), 1f)
        }

        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        set1i("uHeight", 0)
        GLES30.glUniform2f(u["uRes"]!!, vw.toFloat(), vh.toFloat())
        set1i("uMode", mode.id)
        set1i("uSmooth", 1)
        set1f("uInterval", cur.interval)
        set1f("uMajorEvery", majorEvery)
        set1f("uLineWidth", lineWidth * pxScale())
        set1f("uMajorWidth", majorWidth * pxScale())
        set1f("uMinorBright", minorBright)
        set1f("uMajorBright", majorBright)
        set1f("uSweepH", sweepH)
        set1f("uRiseRange", riseLevels * cur.interval)
        set1f("uGlowRange", glowLevels * cur.interval + 1e-3f)
        set1f("uGlowBright", glowBright)
        set1f("uDenseMax", denseMax)
        set1f("uFade", fade)

        if (mode == Mode.ZOOM) {
            val z = 1f - driftZoom * progress
            val ang = cur.lon * 7f + cur.lat * 3f
            val pan = driftPan * (progress - 0.5f) * cur.hh0 * 2f
            GLES30.glUniform2f(u["uZoomCenter"]!!, cur.lat + sin(ang) * pan, cur.lon + cos(ang) * pan / cos(cur.lat))
            set1f("uZoomHalfH", cur.hh0 * z)
            GLES30.glUniform4f(u["uExtract"]!!, cur.extract[0], cur.extract[1], cur.extract[2], cur.extract[3])
        } else {
            set1f("uRotation", simT * 2f * PI.toFloat() / revSec)
            set1f("uTilt", (tiltDeg + tiltWobbleDeg * sin(simT * 2f * PI.toFloat() / wobbleSec)) * DEG)
            set1f("uGlobeRadius", globeRadius)
            if (mode == Mode.LIT) {
                val az = lightAzDeg * DEG; val el = lightElDeg * DEG
                GLES30.glUniform3f(u["uLightDir"]!!, sin(az) * cos(el), cos(az) * cos(el), sin(el))
                set1f("uLitSlope", litSlope); set1f("uLitSoft", litSoft); set1f("uLitAmbient", litAmbient)
            }
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Line widths are specified for a 1080p-class canvas; scale them with the actual surface. */
    private fun pxScale() = max(1f, vh / 1080f)

    // ---- loading ----------------------------------------------------------------------------------

    private fun readManifest() {
        val j = JSONObject(context.assets.open("moon/moon.json").bufferedReader().readText())
        scale = j.getDouble("scale_m_per_dn").toFloat()
        fun grid(o: JSONObject) = Grid(o.getInt("width"), o.getInt("height"), o.getDouble("ppd").toFloat(),
            o.getDouble("lat_min").toFloat(), o.getDouble("lat_max").toFloat())
        globeGrid = grid(j.getJSONObject("globe"))
        zoomGrid = grid(j.getJSONObject("zoom"))
    }

    private fun startLoad(block: () -> Field) {
        if (loading) return
        loading = true
        thread(name = "moon-load", isDaemon = true) {
            try { pending = block() } catch (e: Throwable) { Log.e(TAG, "load failed", e) } finally { loading = false }
        }
    }

    /** Full-sphere height field for the globe modes (box-downsampled if the GPU can't take it). */
    private fun loadGlobe(): Field {
        val g = globeGrid
        val down = g.width > maxTex          // 2x2 box-downsample if the GPU can't take the full grid
        val w = if (down) g.width / 2 else g.width
        val h = if (down) g.height / 2 else g.height
        val data = FloatArray(w * h)
        context.assets.open("moon/ldem_16.bin").use { ins ->
            val buf = ByteArray(g.width * 2)
            val row = ShortArray(g.width)
            for (y in 0 until g.height) {
                var off = 0
                while (off < buf.size) { val n = ins.read(buf, off, buf.size - off); if (n < 0) error("short read"); off += n }
                ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(row)
                if (!down) {
                    val base = y * g.width
                    for (x in 0 until g.width) data[base + x] = row[x] * scale
                } else {
                    val base = (y / 2) * w
                    val k = 0.25f * scale
                    for (x in 0 until w) data[base + x] += (row[2 * x] + row[2 * x + 1]) * k
                }
            }
        }
        // Sweep quantiles, cos(lat)-weighted so the sweep advances by equal area.
        val r = java.util.Random(12345)
        val q = FloatArray(1 shl 17)
        var k = 0
        while (k < q.size) {
            val y = r.nextInt(h); val x = r.nextInt(w)
            val lat = g.latMax - (y + 0.5f) / h * (g.latMax - g.latMin)
            if (r.nextFloat() > cos(lat * DEG)) continue
            q[k++] = data[y * w + x]
        }
        q.sort()
        return Field(w, h, data, q, if (mode == Mode.LIT) litInterval else globeInterval)
    }

    private fun mapZoom(): ShortBuffer {
        zoomData?.let { return it }
        val bytes: ByteBuffer = try {
            // Stored uncompressed (noCompress "bin"), so it can be memory-mapped straight out of the APK.
            val afd = context.assets.openFd("moon/ldem_32.bin")
            val ch = FileInputStream(afd.fileDescriptor).channel
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            ch.close(); afd.close()
            mapped
        } catch (e: Exception) {
            // Compressed after all: stream it into native memory (outside the Java heap limit).
            Log.w(TAG, "ldem_32.bin is not mappable, reading it fully", e)
            val size = zoomGrid.width * zoomGrid.height * 2
            val buf = ByteBuffer.allocateDirect(size)
            context.assets.open("moon/ldem_32.bin").use { ins ->
                val chunk = ByteArray(1 shl 20)
                while (buf.hasRemaining()) {
                    val n = ins.read(chunk, 0, min(chunk.size, buf.remaining())); if (n < 0) break
                    buf.put(chunk, 0, n)
                }
            }
            buf.flip(); buf
        }
        return bytes.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().also { zoomData = it }
    }

    /** Pick the roughest of a few random windows and extract it (with margin for the drift). */
    private fun pickWindow(): Field {
        val src = mapZoom()
        val aspect = vw.toFloat() / vh
        var best: Field? = null
        var bestRough = -1f
        repeat(pickBest) {
            val hhDeg = halfHeightMinDeg + rnd.nextFloat() * (halfHeightMaxDeg - halfHeightMinDeg)
            val latDeg = (rnd.nextFloat() * 2f - 1f) * latMaxDeg
            val lonDeg = rnd.nextFloat() * 360f
            val (f, rough) = extractWindow(src, latDeg, lonDeg, hhDeg, aspect)
            if (rough > bestRough) { best = f; bestRough = rough }
        }
        return best!!
    }

    private fun extractWindow(src: ShortBuffer, latDeg: Float, lonDeg: Float, hhDeg: Float, aspect: Float): Pair<Field, Float> {
        val m = zoomGrid
        val margin = 1f + driftPan * 2f + 0.04f
        val hh = hhDeg * DEG * margin
        val latTop = min(latDeg * DEG + hh, m.latMax * DEG); val latBot = max(latDeg * DEG - hh, m.latMin * DEG)
        val cosMin = min(cos(latTop), cos(latBot))
        val hw = hhDeg * DEG * aspect / max(cosMin, 0.2f) * margin
        val pxPerRad = m.ppd / DEG
        val row0 = max(0, floor((m.latMax * DEG - latTop) * pxPerRad).toInt())
        val row1 = min(m.height, ceil((m.latMax * DEG - latBot) * pxPerRad).toInt())
        val col0 = floor((lonDeg * DEG - hw) * pxPerRad).toInt()
        val col1 = ceil((lonDeg * DEG + hw) * pxPerRad).toInt()
        val w = col1 - col0; val h = row1 - row0
        var data = FloatArray(w * h)
        var sum = 0.0; var sum2 = 0.0
        for (y in 0 until h) {
            val rowBase = (row0 + y) * m.width
            for (x in 0 until w) {
                var cx = (col0 + x) % m.width; if (cx < 0) cx += m.width
                val hm = src.get(rowBase + cx) * scale
                data[y * w + x] = hm; sum += hm; sum2 += hm.toDouble() * hm
            }
        }
        val n = w * h; val mean = sum / n
        val rough = sqrt(max(sum2 / n - mean * mean, 0.0)).toFloat()
        if (windowBlur > 0f) data = gaussianBlur(data, w, h, windowBlur)

        // Sweep quantiles and slope percentile over the on-screen (central) part only.
        val r = java.util.Random(777)
        val q = FloatArray(65536); val gr = FloatArray(65536)
        for (i in q.indices) {
            val x = ((0.5f + (r.nextFloat() - 0.5f) / margin) * (w - 2)).toInt() + 1
            val y = ((0.5f + (r.nextFloat() - 0.5f) / margin) * (h - 2)).toInt() + 1
            val j = y * w + x
            q[i] = data[j]
            gr[i] = abs(data[j + 1] - data[j - 1]) * 0.5f + abs(data[j + w] - data[j - w]) * 0.5f
        }
        q.sort(); gr.sort()
        val screenPxPerSrcPx = vh / (2f * hhDeg * DEG * pxPerRad)
        val interval = max(minInterval, niceInterval(gr[(gr.size * 0.8f).toInt()] / screenPxPerSrcPx / autoDensity))
        val extract = floatArrayOf(col0 / pxPerRad, m.latMax * DEG - row0 / pxPerRad, w / pxPerRad, h / pxPerRad)
        return Field(w, h, data, q, interval, latDeg * DEG, lonDeg * DEG, hhDeg * DEG, extract) to rough
    }

    private fun quantile(q: FloatArray, p: Float): Float = q[max(0, min(q.size - 1, (p * (q.size - 1)).toInt()))]

    private fun niceInterval(x: Float): Float {
        var best = NICE[0]
        for (n in NICE) if (abs(ln(n / x)) < abs(ln(best / x))) best = n
        return best
    }

    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        val r = max(1, ceil(sigma * 2.5f).toInt())
        val k = FloatArray(2 * r + 1); var ks = 0f
        for (i in -r..r) { k[i + r] = exp(-i * i / (2f * sigma * sigma)); ks += k[i + r] }
        for (i in k.indices) k[i] /= ks
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) { val b = y * w
            for (x in 0 until w) { var s = 0f
                for (i in -r..r) { val xx = (x + i).coerceIn(0, w - 1); s += src[b + xx] * k[i + r] }
                tmp[b + x] = s } }
        for (y in 0 until h) for (x in 0 until w) { var s = 0f
            for (i in -r..r) { val yy = (y + i).coerceIn(0, h - 1); s += tmp[yy * w + x] * k[i + r] }
            out[y * w + x] = s }
        return out
    }

    // ---- GL helpers -------------------------------------------------------------------------------

    /** (Re)allocate the R16F texture and upload the field in row chunks (keeps direct-buffer use small). */
    private fun upload(f: Field) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        if (f.w != texW || f.h != texH) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, f.w, f.h, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
            texW = f.w; texH = f.h
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, if (mode == Mode.ZOOM) GLES30.GL_CLAMP_TO_EDGE else GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val data = f.data ?: return
        val rows = max(1, (4 shl 20) / (f.w * 4))     // ~4 MB per chunk
        val buf: FloatBuffer = GlUtil.directFloats(rows * f.w)
        var y = 0
        while (y < f.h) {
            val n = min(rows, f.h - y)
            buf.clear(); buf.put(data, y * f.w, n * f.w); buf.flip()
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, y, f.w, n, GLES30.GL_RED, GLES30.GL_FLOAT, buf)
            y += n
        }
    }

    private fun set1f(name: String, v: Float) { u[name]?.let { if (it >= 0) GLES30.glUniform1f(it, v) } }
    private fun set1i(name: String, v: Int) { u[name]?.let { if (it >= 0) GLES30.glUniform1i(it, v) } }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader compile failed: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "program link failed: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "MoonRenderer"
        private const val DEG = (PI / 180.0).toFloat()
        private val NICE = floatArrayOf(10f, 20f, 25f, 50f, 75f, 100f, 150f, 200f, 250f, 300f, 400f, 500f, 600f, 800f, 1000f, 1500f, 2000f)
        private val UNIFORMS = listOf("uHeight", "uRes", "uMode", "uZoomCenter", "uZoomHalfH", "uExtract", "uSmooth", "uRotation",
            "uTilt", "uGlobeRadius", "uLightDir", "uLitSlope", "uLitSoft", "uLitAmbient", "uInterval", "uMajorEvery", "uLineWidth",
            "uMajorWidth", "uMinorBright", "uMajorBright", "uSweepH", "uRiseRange", "uGlowRange", "uGlowBright", "uDenseMax", "uFade")
        private const val VS = """#version 300 es
void main() {
  vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
  gl_Position = vec4(p, 0.0, 1.0);
}
"""
    }
}
