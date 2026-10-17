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
import kotlin.math.acos
import kotlin.math.asin
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
 * Topographic contour maps of the Moon or the Earth (white lines on black): the Moon from the LRO LOLA
 * height model in assets/moon (tools/moon_data.py), the Earth from NOAA ETOPO 2022 land + sea-floor relief
 * in assets/earth (tools/earth_data.py); the Earth close-up can fill the bands between the lines with sea / land
 * colour ramps and names countries, seas and landmarks ([MapLabels]). Every mode shares one fragment shader (assets/moon/moon.frag,
 * also used verbatim by preview/moon.html). All look and timing values are live [Tweaks] (defaults in
 * [SceneParams.moon]), adjusted from the preview's tweak panel on the TV:
 *
 *  ZOOM  — a random window of the surface; a sweep rises from the lowest to the highest point and
 *          draws each contour as it passes, holds, fades out, and picks a new window.
 *          Every contour level gets the same share of the sweep time, and each fades in over
 *          `lineFadeSec`, so no stretch of the sweep rushes or pops.
 *  GLOBE — a slowly spinning orthographic globe with the same continuous sweep.
 *  LIT   — the spinning globe with a light behind the camera, off to one side: only the contour
 *          segments on slopes that face the light are drawn, like a hatched engraving.
 *  PULSE — the spinning globe with every line always drawn at a dim resting level, while pulses of
 *          light run endlessly through the elevations (no sweep / hold / fade-out cycle).
 *
 * Requires a GL ES 3.0 context (R16F height textures, `#version 300 es`).
 */
class MoonRenderer(
    private val context: Context,
    private val mode: Mode,
    private val tweaks: Tweaks,
    private val body: Body = Body.MOON,
) : GLSurfaceView.Renderer, SceneStatus {

    enum class Mode(val id: Int) { ZOOM(0), GLOBE(1), LIT(2), PULSE(1) }   // id = shader uMode

    /** Which height data: assets/<dir>/<dir>.json describes the grids in that folder. */
    enum class Body(val dir: String) { MOON("moon"), EARTH("earth") }

    // ---- fixed settings (everything adjustable is in [tweaks], see SceneParams.moon) ---------------
    private val latMaxDeg get() = zoomGrid.latMax - 12f    // zoom window centres stay this far inside the grid
    private val candidates = 3          // windows tried per pick; the flattest is dropped, one of the rest is shown
    private val recentCount = 20         // window centres remembered so new picks keep away from them
    private val recentSpacing = 1.5f     // ... by at least this many window half-heights (the larger of the two)
    private val wobbleSec = 600f

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
    private class Grid(val file: String, val width: Int, val height: Int, val ppd: Float, val latMin: Float, val latMax: Float)
    private var scale = 0.5f
    private var radius = 1737400f                        // body radius (m), for the lit mode's slopes
    private lateinit var globeGrid: Grid
    private lateinit var zoomGrid: Grid
    private var zoomData: ShortBuffer? = null           // memory-mapped 32 px/deg grid

    /** A prepared height field ready for upload, plus everything the sweep needs. */
    private class Field(
        val w: Int, val h: Int, var data: FloatArray?, val quant: FloatArray,
        // zoom only: metres of rise per screen px at the 80th-percentile slope (sets the contour interval)
        val gradRef: Float = 0f,
        val lat: Float = 0f, val lon: Float = 0f, val hh0: Float = 0f, val extract: FloatArray = FloatArray(4),
    ) {
        var names: CraterLabels.Batch? = null           // moon zoom: crater names for this window
        var map: MapLabels.Batch? = null                // earth zoom: countries, seas and landmarks in view
        val lo = quant.first()
        val hi = quant.last()
    }
    @Volatile private var pending: Field? = null
    @Volatile private var loading = false
    private var current: Field? = null
    /** Crater names: only the moon close-up has them. */
    private val labels = if (body == Body.MOON && mode == Mode.ZOOM) CraterLabels(context) else null
    /** Country, sea and landmark names: only the Earth close-up has them. */
    private val mapLabels = if (body == Body.EARTH && mode == Mode.ZOOM) MapLabels(context) else null

    // ---- animation --------------------------------------------------------------------------------
    private enum class Phase { SWEEP, HOLD, FADE }
    private var phase = Phase.SWEEP
    private var phaseT = 0f
    private var cycleT = 0f
    private var simT = 0f
    private var lastNanos = 0L
    private val rnd = java.util.Random()
    private val recent = ArrayDeque<FloatArray>()      // (lat, lon, half-height) radians of the last zoom windows
    private var lastSweepH = Float.NaN

    // ---- readout for the preview's info overlay (written on the GL thread) -------------------------
    @Volatile private var stPhase = ""
    @Volatile private var stProgress = 0f
    @Volatile private var stSweepH = 0f
    @Volatile private var stLo = 0f
    @Volatile private var stHi = 0f
    @Volatile private var stInterval = 1f
    @Volatile private var stRate = 0f        // metres per second, smoothed

    override fun status(): String {
        if (stPhase.isEmpty()) return "loading height data…"
        val iv = stInterval
        val sweep = if (stPhase == "SWEEP") "%+.0f m".format(stSweepH) else "complete"
        val speed = if (stPhase == "SWEEP" && stRate > 0f)
            "%.0f m/s · a new line every %.1f s".format(stRate, iv / stRate) else "—"
        return "$stPhase ${(stProgress * 100).toInt()}% · sweep $sweep\n" +
            "elevation %+.0f … %+.0f m · interval %.0f m (%d levels)\n".format(stLo, stHi, iv, ((stHi - stLo) / iv).toInt()) +
            "sweep speed: $speed"
    }

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
        labels?.initGl(::link)
        mapLabels?.initGl(::link)

        readManifest()
        current = null; pending = null; loading = false
        phase = Phase.SWEEP; phaseT = 0f; cycleT = 0f; simT = 0f; lastNanos = 0L; rotation = 0f; pulsePos = 0f
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

        // Swap in a freshly prepared field (first load, or the next zoom window after a fade).
        pending?.let { f ->
            if (current == null || phase == Phase.FADE && phaseT >= fadeSec) {
                upload(f); f.data = null; current = f; pending = null
                f.names?.let { labels?.show(it) }
                f.map?.let { mapLabels?.show(it) }
                phase = Phase.SWEEP; phaseT = 0f; cycleT = 0f
            }
        }
        val cur = current
        if (cur == null) { GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
        simT += dt      // counts from the first drawn frame, so the 2 s fade-in isn't used up while loading

        val t = tweaks
        val interval = intervalFor(cur)
        val down = t.int("direction") == 1          // sweep from the highest point down
        val sweepOn = mode == Mode.ZOOM || mode == Mode.GLOBE || mode == Mode.LIT && t.bool("litSweep")
        val sweepSec = if (sweepOn) t["sweepSec"] else 1f
        val holdSec = if (sweepOn) t["holdSec"] else 0f
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
            // Constant level clock: level i of n is crossed at (i + 0.5) / n of the sweep time. It keeps
            // running through HOLD and FADE so the last lines finish fading in and their glow decays.
            val crossed = cycleT * levelRate(cur, interval, sweepSec)
            sweepH = if (down) (floor(cur.hi / interval) + 0.5f - crossed) * interval
                     else (ceil(cur.lo / interval) - 0.5f + crossed) * interval
            fade = when (phase) {
                Phase.FADE -> max(0f, 1f - phaseT / fadeSec)
                Phase.SWEEP -> min(1f, phaseT / 1f)
                Phase.HOLD -> 1f
            }
            progress = min(cycleT / (sweepSec + holdSec + fadeSec), 1f)
        }
        updateStatus(cur, sweepOn, sweepH, interval, progress, dt)

        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        set1i("uHeight", 0)
        GLES30.glUniform2f(u["uRes"]!!, vw.toFloat(), vh.toFloat())
        set1i("uMode", mode.id)
        set1i("uSmooth", 1)
        set1f("uInterval", interval)
        set1f("uMajorEvery", t["majorEvery"])
        set1f("uLineWidth", t["lineWidth"] * pxScale())
        set1f("uMajorWidth", t["majorWidth"] * pxScale())
        set1f("uMinorBright", t["minorBright"])
        set1f("uMajorBright", t["majorBright"])
        set1f("uSweepH", sweepH)
        set1f("uSweepDir", if (down) -1f else 1f)
        if (mode == Mode.PULSE) {
            // Fronts move pulseSpeed metres of elevation per second; the position wraps every spacing.
            val spacing = t["pulseSpacing"]
            pulsePos = (pulsePos + dt * t["pulseSpeed"] * if (down) -1f else 1f).mod(spacing)
            set1i("uPulse", 1)
            set1f("uPulsePos", pulsePos)
            set1f("uPulseSpacing", spacing)
            set1f("uRiseRange", t["lineFadeSec"] * t["pulseSpeed"])
            set1f("uGlowRange", t["pulseTrail"] + 1e-3f)
        } else {
            set1i("uPulse", 0)
            set1f("uRiseRange", t["lineFadeSec"] * levelRate(cur, interval, sweepSec) * interval)
            set1f("uGlowRange", t["glowLevels"] * interval + 1e-3f)
        }
        set1f("uGlowBright", t["glowBright"])
        set1f("uDenseMax", t["denseMax"])
        set1f("uFade", fade)
        set1f("uBodyR", radius)
        GLES30.glUniform2f(u["uHRange"]!!, quantile(cur.quant, 0.005f), quantile(cur.quant, 0.995f))
        set1f("uTint", t["tint"])
        set1f("uLowDim", t["lowDim"])
        set1f("uEarthCol", if (body == Body.EARTH && t.bool("earthColors")) 1f else 0f)
        if (body == Body.EARTH) set1f("uSnowLine", t["snowLine"])
        if (body == Body.EARTH && mode == Mode.ZOOM) {
            set1f("uFill", t["fill"])
            setColor("uSea0", t.int("seaShallow")); setColor("uSea1", t.int("seaMid")); setColor("uSea2", t.int("seaDeep"))
            setColor("uLand0", t.int("landLow")); setColor("uLand1", t.int("landMid")); setColor("uLand2", t.int("landHigh"))
            set1f("uSeaDepth", t["seaDepth"]); set1f("uLandTop", t["landTop"])
        } else set1f("uFill", 0f)

        if (mode == Mode.ZOOM) {
            val z = 1f - t["driftZoom"] * progress
            val ang = cur.lon * 7f + cur.lat * 3f
            val pan = t["driftPan"] * (progress - 0.5f) * cur.hh0 * 2f
            val cLat = cur.lat + sin(ang) * pan
            val cLon = cur.lon + cos(ang) * pan / cos(cur.lat)
            GLES30.glUniform2f(u["uZoomCenter"]!!, cLat, cLon)
            set1f("uZoomHalfH", cur.hh0 * z)
            GLES30.glUniform4f(u["uExtract"]!!, cur.extract[0], cur.extract[1], cur.extract[2], cur.extract[3])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            // A name fades in with the contour line it sits on, exactly as moon.frag fades in that line.
            val rise = max(t["lineFadeSec"] * levelRate(cur, interval, sweepSec) * interval, 1e-3f)
            val dir = if (down) -1f else 1f
            if (labels != null && t.bool("craterNames")) {
                labels.draw(cLat, cLon, cur.hh0 * z, vw, vh, fade, t["labelBright"], interval) { level ->
                    smoothstep(0f, rise, (sweepH - level) * dir)
                }
            }
            mapLabels?.draw(cLat, cLon, cur.hh0 * z, vw, vh, fade, t.bool("placeNames"), t["placeBright"],
                t.bool("landmarkNames"), t["landmarkBright"]) { h ->
                // The first level the sweep crosses after it has covered the named spot.
                val level = (if (down) floor(h / interval) else ceil(h / interval)) * interval
                smoothstep(0f, rise, (sweepH - level) * dir)
            }
            return
        } else {
            rotation += dt * 2f * PI.toFloat() / t["revSec"]
            set1f("uRotation", rotation)
            set1f("uTilt", (t["tiltDeg"] + t["tiltWobbleDeg"] * sin(simT * 2f * PI.toFloat() / wobbleSec)) * DEG)
            set1f("uGlobeRadius", t["globeRadius"])
            if (mode == Mode.LIT) {
                val az = t["lightAzDeg"] * DEG; val el = t["lightElDeg"] * DEG
                GLES30.glUniform3f(u["uLightDir"]!!, sin(az) * cos(el), cos(az) * cos(el), sin(el))
                set1f("uLitSlope", t["litSlope"]); set1f("uLitSoft", t["litSoft"]); set1f("uLitAmbient", t["litAmbient"])
            }
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private var rotation = 0f
    private var pulsePos = 0f
    private val fadeSec get() = tweaks["fadeSec"]

    /** Contour levels crossed per second: every level between the field's extremes gets the same time. */
    private fun levelRate(f: Field, interval: Float, sweepSec: Float): Float =
        (floor(f.hi / interval) - ceil(f.lo / interval) + 1f).coerceAtLeast(1f) / sweepSec

    private fun intervalFor(f: Field): Float =
        if (mode == Mode.ZOOM) max(tweaks["minInterval"], niceInterval(f.gradRef / tweaks["autoDensity"]))
        else tweaks["interval"]

    private fun updateStatus(cur: Field, sweepOn: Boolean, sweepH: Float, interval: Float, progress: Float, dt: Float) {
        val sweeping = sweepOn && phase == Phase.SWEEP
        if (sweeping && !lastSweepH.isNaN() && dt > 0f) {
            val r = abs(sweepH - lastSweepH) / dt
            stRate += (r - stRate) * min(1f, dt * 2f)       // ~0.5 s smoothing
        }
        lastSweepH = if (sweeping) sweepH else Float.NaN
        stPhase = if (mode == Mode.PULSE) "PULSE" else if (!sweepOn) "STATIC" else phase.name
        stProgress = progress; stSweepH = sweepH; stLo = cur.lo; stHi = cur.hi; stInterval = interval
    }

    /** Line widths are specified for a 1080p-class canvas; scale them with the actual surface. */
    private fun pxScale() = max(1f, vh / 1080f)

    // ---- loading ----------------------------------------------------------------------------------

    private fun readManifest() {
        val j = JSONObject(context.assets.open("${body.dir}/${body.dir}.json").bufferedReader().readText())
        scale = j.getDouble("scale_m_per_dn").toFloat()
        radius = j.optDouble("radius_m", 1737400.0).toFloat()
        fun grid(o: JSONObject) = Grid("${body.dir}/" + o.getString("file"), o.getInt("width"), o.getInt("height"), o.getDouble("ppd").toFloat(),
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
        context.assets.open(g.file).use { ins ->
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
        return Field(w, h, data, q)
    }

    private fun mapZoom(): ShortBuffer {
        zoomData?.let { return it }
        val bytes: ByteBuffer = try {
            // Stored uncompressed (noCompress "bin"), so it can be memory-mapped straight out of the APK.
            val afd = context.assets.openFd(zoomGrid.file)
            val ch = FileInputStream(afd.fileDescriptor).channel
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            ch.close(); afd.close()
            mapped
        } catch (e: Exception) {
            // Compressed after all: stream it into native memory (outside the Java heap limit).
            Log.w(TAG, "${zoomGrid.file} is not mappable, reading it fully", e)
            val size = zoomGrid.width * zoomGrid.height * 2
            val buf = ByteBuffer.allocateDirect(size)
            context.assets.open(zoomGrid.file).use { ins ->
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

    /**
     * Pick a window and extract it (with margin for the drift). A few random candidates, uniform by area and away
     * from the last [recentCount] windows, are extracted; the flattest is dropped (mostly featureless plains or
     * sea floor) and one of the others is chosen at random. Always taking the roughest kept returning to the
     * same few rugged regions.
     */
    private fun pickWindow(): Field {
        val src = mapZoom()
        val aspect = vw.toFloat() / vh
        val fields = List(candidates) {
            val (latDeg, lonDeg, hhDeg) = randomCentre()
            extractWindow(src, latDeg, lonDeg, hhDeg, aspect)
        }.sortedBy { it.second }
        val f = fields[1 + rnd.nextInt(fields.size - 1)].first
        recent.addLast(floatArrayOf(f.lat, f.lon, f.hh0))
        while (recent.size > recentCount) recent.removeFirst()
        labels?.let { l ->
            f.names = l.build(f.lat, f.lon, f.hh0, vw, vh, radius, tweaks.int("labelMax"), tweaks["labelMinSize"],
                tweaks["labelSize"] * pxScale(), f.data!!, f.w, f.h, f.extract)
        }
        mapLabels?.let { m ->
            val px = pxScale()
            val s = MapLabels.Settings(tweaks.bool("placeNames"), tweaks.int("placeMax"), tweaks["placeSize"] * px,
                tweaks.bool("landmarkNames"), tweaks.int("landmarkMax"), tweaks["landmarkSize"] * px,
                1f / (1f - tweaks["driftZoom"]), tweaks["driftPan"] * vh / 2f)
            f.map = m.build(f.lat, f.lon, f.hh0, vw, vh, s, f.data!!, f.w, f.h, f.extract)
        }
        return f
    }

    /** A random (lat, lon, half-height) in degrees, uniform by area, not near a recent window (if one can be found). */
    private fun randomCentre(): Triple<Float, Float, Float> {
        val a = tweaks["windowMinDeg"]; val b = max(a, tweaks["windowMaxDeg"])
        var c = Triple(0f, 0f, a)
        for (attempt in 0 until 30) {
            val hhDeg = a + rnd.nextFloat() * (b - a)
            val latDeg = asin((rnd.nextFloat() * 2f - 1f) * sin(latMaxDeg * DEG)) / DEG
            val lonDeg = rnd.nextFloat() * 360f
            c = Triple(latDeg, lonDeg, hhDeg)
            if (recent.none { r -> angle(latDeg * DEG, lonDeg * DEG, r[0], r[1]) < recentSpacing * max(hhDeg * DEG, r[2]) }) break
        }
        return c
    }

    /** Great-circle angle between two (lat, lon) points, radians. */
    private fun angle(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Float =
        acos((sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(lon1 - lon2)).coerceIn(-1f, 1f))

    private fun extractWindow(src: ShortBuffer, latDeg: Float, lonDeg: Float, hhDeg: Float, aspect: Float): Pair<Field, Float> {
        val m = zoomGrid
        val margin = 1f + tweaks.param("driftPan").max * 2f + 0.04f   // room for the largest pan setting
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
        val blur = tweaks["windowBlur"]
        if (blur > 0f) data = gaussianBlur(data, w, h, blur)

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
        val gradRef = gr[(gr.size * 0.8f).toInt()] / screenPxPerSrcPx
        val extract = floatArrayOf(col0 / pxPerRad, m.latMax * DEG - row0 / pxPerRad, w / pxPerRad, h / pxPerRad)
        return Field(w, h, data, q, gradRef, latDeg * DEG, lonDeg * DEG, hhDeg * DEG, extract) to rough
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
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
    private fun setColor(name: String, rgb: Int) {
        u[name]?.let { if (it >= 0) GLES30.glUniform3f(it, (rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f) }
    }

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
            "uMajorWidth", "uMinorBright", "uMajorBright", "uSweepH", "uRiseRange", "uGlowRange", "uGlowBright", "uDenseMax", "uFade",
            "uSweepDir", "uHRange", "uTint", "uLowDim", "uPulse", "uPulsePos", "uPulseSpacing", "uBodyR", "uEarthCol", "uSnowLine",
            "uFill", "uSea0", "uSea1", "uSea2", "uLand0", "uLand1", "uLand2", "uSeaDepth", "uLandTop")
        private const val VS = """#version 300 es
void main() {
  vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
  gl_Position = vec4(p, 0.0, 1.0);
}
"""
    }
}
