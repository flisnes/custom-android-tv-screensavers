package com.fleece.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Map names over the Earth close-up ([MoonRenderer] ZOOM), built per window on the load thread ([build]) and drawn
 * every frame with moon.frag's projection, so they follow the zoom/pan drift ([draw]).
 *
 *  - Countries, oceans and seas (assets/earth/places.grid + places.tsv, tools/earth_names.py): every one that fills
 *    a good part of the view is named at the point of its visible area farthest from its edges (and the screen's),
 *    in large spaced capitals; seas in italics.
 *  - Landmarks (assets/earth/landmarks.tsv, tools/earth_landmarks.py): peaks and depressions (with a small
 *    triangle on the spot), ranges, plateaus and deserts, rivers, lakes, canyons, trenches, ridges, reefs, straits
 *    and fjords, in smaller lettering. Lines (rivers, trenches, ridges, long ranges) are named along the visible
 *    part of the line. The most prominent come first, with a penalty for repeating a kind so a window does not
 *    show only rivers, and no name may overlap another.
 *
 * Each name fades in as the sweep reaches the ground under it: a country with its coastline (0 m).
 */
class MapLabels(private val context: Context) {

    class Settings(
        val places: Boolean, val placeMax: Int, val placeSize: Float,
        val landmarks: Boolean, val landmarkMax: Int, val landmarkSize: Float,
        /** How far names drift outward during a cycle (the zoom drift's 1 / end scale) and the pan drift in px. */
        val spread: Float = 1f, val panPx: Float = 0f,
    )

    internal class Label(val lat: Float, val lon: Float, val angle: Float, val revealH: Float, val g: MapText.Glyphs,
                         val rgb: Int, val place: Boolean)

    /** The names of one window, prepared off the GL thread. */
    class Batch internal constructor(internal val labels: List<Label>, internal var atlas: Bitmap?)

    /** Lettering styles: caps, italic, tint, marker. */
    private enum class Style(val caps: Boolean, val italic: Boolean, val rgb: Int, val tracking: Float = 0.04f,
                             val marker: MapText.Marker = MapText.Marker.NONE) {
        COUNTRY(true, false, 0xFFFFFF, 0.22f),
        SEA(true, true, 0xD2E8FF, 0.22f),
        REGION(true, false, 0xF4ECDC, 0.14f),           // ranges, plateaus, deserts, plains
        FEATURE(false, false, 0xF4ECDC),                 // canyons, valleys, deltas, wetlands
        PEAK(false, false, 0xFFFFFF, marker = MapText.Marker.PEAK),
        LOW(false, false, 0xFFFFFF, marker = MapText.Marker.LOW),
        WATER(false, true, 0xB8DCFF),                    // rivers, lakes, small seas, straits, fjords
        UNDERSEA(false, true, 0xC4ECF4, 0.08f),         // trenches, ridges
        REEF(false, true, 0xFFD2BE),
    }

    private class Places(val land: BooleanArray, val names: List<String>, val grid: ShortArray)
    private class Landmark(val kind: String, val rank: Int, val name: String, val value: Float, val lat: FloatArray, val lon: FloatArray)

    /** A name that could be shown: where, at what angle, and its box on screen (for the overlap test). */
    private class Cand(val lat: Float, val lon: Float, val angle: Float, val revealH: Float, val entry: MapText.Entry,
                       val rgb: Int, val box: Box, val place: Boolean, val key: Float = 0f, val group: String = "")

    /** An oriented rectangle on screen: centre, half sizes, angle. */
    private class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val a: Float) {
        fun overlaps(o: Box): Boolean {
            for (ang in floatArrayOf(a, a + HALF_PI, o.a, o.a + HALF_PI)) {
                val ux = cos(ang); val uy = sin(ang)
                val d = abs((o.cx - cx) * ux + (o.cy - cy) * uy)
                if (d > extent(ux, uy) + o.extent(ux, uy)) return false
            }
            return true
        }
        private fun extent(ux: Float, uy: Float) = hw * abs(cos(a) * ux + sin(a) * uy) + hh * abs(-sin(a) * ux + cos(a) * uy)
        /** On screen with margin [m] for the whole cycle, as the drift moves it outward by [spread] from the centre. */
        fun inside(vw: Int, vh: Int, m: Float, spread: Float): Boolean {
            val ex = extent(1f, 0f); val ey = extent(0f, 1f)
            val x = vw / 2f + (cx - vw / 2f) * spread; val y = vh / 2f + (cy - vh / 2f) * spread
            return x - ex >= m && x + ex <= vw - m && y - ey >= m && y + ey <= vh - m
        }
    }

    private val places: Places? by lazy {
        try {
            val lines = context.assets.open("earth/places.tsv").bufferedReader().readLines().map { it.split('\t') }
            val grid = ShortArray(W * H)
            val bytes = ByteArray(W * H * 2)
            DataInputStream(GZIPInputStream(context.assets.open("earth/places.grid"), 1 shl 16)).use { it.readFully(bytes) }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(grid)
            Places(BooleanArray(lines.size + 1) { it > 0 && lines[it - 1][0] == "land" },
                listOf("") + lines.map { it[1] }, grid)
        } catch (e: Exception) { Log.e(TAG, "place names missing", e); null }
    }

    private val landmarks: List<Landmark> by lazy {
        try {
            context.assets.open("earth/landmarks.tsv").bufferedReader().readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                val pts = p[4].split(';').map { it.split(',') }
                Landmark(p[0], p[1].toInt(), p[2], p[3].toFloat(),
                    FloatArray(pts.size) { pts[it][0].toFloat() * DEG }, FloatArray(pts.size) { pts[it][1].toFloat() * DEG })
            }
        } catch (e: Exception) { Log.e(TAG, "landmarks missing", e); emptyList() }
    }

    private val layer = MapText.Layer(MAX_PLACES + MAX_LANDMARKS)
    private var current: List<Label> = emptyList()

    /**
     * Picks and renders the names for a window centred on ([lat], [lon]) radians with half-height [hh] radians.
     * [data] ([w] x [h]) is the window's height field, covering [extract] (lon0, lat0, dlon, dlat as in moon.frag).
     */
    fun build(lat: Float, lon: Float, hh: Float, vw: Int, vh: Int, s: Settings,
              data: FloatArray, w: Int, h: Int, extract: FloatArray): Batch {
        val view = View(lat, lon, hh, vw, vh)
        val heightAt = { la: Float, lo: Float ->
            val x = ((lon + wrapPi(lo - lon) - extract[0]) / extract[2] * w).toInt().coerceIn(0, w - 1)
            val y = ((extract[1] - la) / extract[3] * h).toInt().coerceIn(0, h - 1)
            data[y * w + x]
        }
        val chosen = ArrayList<Cand>()
        if (s.places) placeNames(view, s, heightAt, chosen)
        if (s.landmarks) landmarkNames(view, s, heightAt, chosen)
        if (chosen.isEmpty()) return Batch(emptyList(), null)
        val (bmp, glyphs) = MapText.atlas(chosen.map { it.entry })
        return Batch(chosen.mapIndexed { i, c -> Label(c.lat, c.lon, c.angle, c.revealH, glyphs[i], c.rgb, c.place) }, bmp)
    }

    /** moon.frag's zoom projection and its inverse; screen px with y up. */
    private class View(val lat: Float, val lon: Float, val hh: Float, val vw: Int, val vh: Int) {
        val aspect = vw.toFloat() / vh
        fun x(la: Float, lo: Float) = vw / 2f + wrapPi(lo - lon) * cos(la) / hh * vh / 2f
        fun y(la: Float) = vh / 2f + (la - lat) / hh * vh / 2f
        fun latAt(py: Float) = lat + (py - vh / 2f) / (vh / 2f) * hh
        fun lonAt(px: Float, la: Float) = lon + (px - vw / 2f) / (vh / 2f) * hh / cos(la)
    }

    // ---- countries and seas --------------------------------------------------------------------

    private fun placeNames(v: View, s: Settings, heightAt: (Float, Float) -> Float, chosen: MutableList<Cand>) {
        val p = places ?: return
        val gh = GRID_ROWS; val gw = max(1, (GRID_ROWS * v.aspect).roundToInt())
        val ids = IntArray(gw * gh)
        val count = IntArray(p.names.size)
        for (j in 0 until gh) for (i in 0 until gw) {
            val la = v.latAt((j + 0.5f) / gh * v.vh)
            val lo = v.lonAt((i + 0.5f) / gw * v.vw, la)
            val y = floor((90f - la / DEG) * PPD).toInt().coerceIn(0, H - 1)
            val x = Math.floorMod(floor(lo / DEG * PPD).toInt(), W)
            val id = p.grid[y * W + x].toInt() and 0xffff
            ids[j * gw + i] = id; count[id]++
        }
        val order = count.indices.filter { it > 0 && count[it] >= MIN_SHARE * gw * gh }.sortedByDescending { count[it] }
        val cellPx = v.vh / gh.toFloat()
        var placed = 0
        for (id in order) {
            if (placed >= s.placeMax.coerceAtMost(MAX_PLACES)) break
            val land = p.land[id]
            val style = if (land) Style.COUNTRY else Style.SEA
            val pen = pen(style, s.placeSize)
            val name = p.names[id]
            val tw = pen.width(name); val th = pen.height
            // The visible cell farthest from the place's edge (and the screen's); among near-equals the most central one.
            val dist = chamfer(ids, gw, gh, id)
            var cx = 0f; var cy = 0f
            for (k in ids.indices) if (ids[k] == id) { cx += k % gw; cy += k / gw }
            cx /= count[id]; cy /= count[id]
            var best = -1; var bestScore = -1e9f
            for (k in ids.indices) if (dist[k] > 0f) {
                val sc = dist[k] - 0.15f * hypot(k % gw - cx, k / gw - cy)
                if (sc > bestScore) { bestScore = sc; best = k }
            }
            if (best < 0) continue
            if (placed > 0 && dist[best] * cellPx < th * 0.6f) continue     // a sliver: no room for the name
            // Keep the whole name on screen all cycle long (the drift pushes it outward by up to s.spread).
            val m = 0.03f * v.vh + s.panPx
            val rx = max(0f, (v.vw / 2f - tw / 2 - m) / s.spread); val ry = max(0f, (v.vh / 2f - th / 2 - m) / s.spread)
            val ax = ((best % gw + 0.5f) * cellPx).coerceIn(v.vw / 2f - rx, v.vw / 2f + rx)
            val ay = ((best / gw + 0.5f) * cellPx).coerceIn(v.vh / 2f - ry, v.vh / 2f + ry)
            val box = Box(ax, ay, tw / 2 + th * 0.3f, th / 2 + th * 0.15f, 0f)
            if (chosen.any { it.box.overlaps(box) }) continue
            val la = v.latAt(ay); val lo = v.lonAt(ax, la)
            chosen += Cand(la, lo, 0f, if (land) 0f else heightAt(la, lo), MapText.Entry(name, pen), style.rgb, box, true)
            placed++
        }
    }

    /** Chamfer distance (in cells) from each cell of [id] to the nearest cell that is not [id] or off screen. */
    private fun chamfer(ids: IntArray, gw: Int, gh: Int, id: Int): FloatArray {
        val d = FloatArray(ids.size) { if (ids[it] == id) 1e9f else 0f }
        val dg = 1.4142f
        fun at(i: Int, j: Int) = if (i < 0 || j < 0 || i >= gw || j >= gh) 0f else d[j * gw + i]
        for (j in 0 until gh) for (i in 0 until gw) {
            val k = j * gw + i
            if (d[k] > 0f) d[k] = min(d[k], min(min(at(i - 1, j) + 1f, at(i, j - 1) + 1f), min(at(i - 1, j - 1) + dg, at(i + 1, j - 1) + dg)))
        }
        for (j in gh - 1 downTo 0) for (i in gw - 1 downTo 0) {
            val k = j * gw + i
            if (d[k] > 0f) d[k] = min(d[k], min(min(at(i + 1, j) + 1f, at(i, j + 1) + 1f), min(at(i + 1, j + 1) + dg, at(i - 1, j + 1) + dg)))
        }
        return d
    }

    // ---- landmarks -----------------------------------------------------------------------------

    private fun landmarkNames(v: View, s: Settings, heightAt: (Float, Float) -> Float, chosen: MutableList<Cand>) {
        val reach = v.hh * sqrt(1f + v.aspect * v.aspect) * 1.1f     // radians from the centre to past a corner
        val m = 0.03f * v.vh + s.panPx
        val cands = ArrayList<Cand>()
        for (lm in landmarks) {
            val style = styleOf(lm.kind) ?: continue
            // Too small to name at this scale (curated canyons have no size).
            if (lm.kind in AREA_KINDS && lm.value > 0f && lm.value * DEG < MIN_AREA * 2f * v.hh) continue
            var near = false
            for (k in lm.lat.indices) {
                if (abs(lm.lat[k] - v.lat) < reach && abs(wrapPi(lm.lon[k] - v.lon)) * cos(lm.lat[k]) < reach) { near = true; break }
            }
            if (!near) continue
            val pen = pen(style, s.landmarkSize)
            val tw = pen.width(lm.name, style.marker); val th = pen.height
            val entry = MapText.Entry(lm.name, pen, style.marker)
            val rankKey = lm.rank + KIND_ORDER.indexOf(groupOf(lm.kind)).coerceAtLeast(0) * 0.05f
            if (lm.lat.size == 1) {
                val ax = v.x(lm.lat[0], lm.lon[0]); val ay = v.y(lm.lat[0])
                // The box's centre: the anchor is the marker (left part) for peaks, else the middle.
                val g = MapText.Glyphs(tw, th, 0f, 1f, 1f, if (style.marker != MapText.Marker.NONE) pen.markerX() else tw / 2)
                val box = Box(ax + tw / 2 - g.ax, ay, tw / 2 + th * 0.2f, th / 2 + th * 0.1f, 0f)
                if (!box.inside(v.vw, v.vh, m, s.spread)) continue
                // Higher peaks first among peaks of the same rank.
                val key = rankKey - if (lm.kind == "peak") lm.value / 1e5f else lm.value / 100f
                cands += Cand(lm.lat[0], lm.lon[0], 0f, heightAt(lm.lat[0], lm.lon[0]), entry, style.rgb, box, false, key, groupOf(lm.kind))
            } else {
                val run = longestRun(v, lm, m + th) ?: continue
                val len = run.last().third
                if (len < tw * 1.15f) continue
                val mid = pointAt(run, len / 2)
                val p0 = pointAt(run, len / 2 - tw / 2); val p1 = pointAt(run, len / 2 + tw / 2)
                var a = atan2(p1.second - p0.second, p1.first - p0.first)
                if (a > HALF_PI) a -= PI.toFloat() else if (a < -HALF_PI) a += PI.toFloat()
                val box = Box(mid.first, mid.second, tw / 2 + th * 0.2f, th / 2 + th * 0.1f, a)
                if (!box.inside(v.vw, v.vh, m * 0.5f, s.spread)) continue
                val la = v.latAt(mid.second); val lo = v.lonAt(mid.first, la)
                cands += Cand(la, lo, a, heightAt(la, lo), entry, style.rgb, box, false, rankKey - len / v.vh * 0.5f, groupOf(lm.kind))
            }
        }
        // Greedy: the best candidate each time, with a penalty per name of the same group already chosen.
        val perGroup = HashMap<String, Int>()
        val names = HashSet<String>()
        var placed = 0
        val left = cands.toMutableList()
        while (placed < s.landmarkMax.coerceAtMost(MAX_LANDMARKS) && left.isNotEmpty()) {
            val c = left.minBy { it.key + REPEAT_PENALTY * (perGroup[it.group] ?: 0) }
            left.remove(c)
            if (c.entry.text in names || chosen.any { it.box.overlaps(c.box) }) continue
            chosen += c; names += c.entry.text
            perGroup[c.group] = (perGroup[c.group] ?: 0) + 1
            placed++
        }
    }

    /** The longest stretch of the landmark's line inside the screen (inset by [m]), as (x, y, length so far) points. */
    private fun longestRun(v: View, lm: Landmark, m: Float): List<Triple<Float, Float, Float>>? {
        var best: List<Triple<Float, Float, Float>>? = null
        var cur = ArrayList<Triple<Float, Float, Float>>()
        fun inside(x: Float, y: Float) = x >= m && x <= v.vw - m && y >= m && y <= v.vh - m
        fun close() { if (cur.size >= 2 && (best == null || cur.last().third > best!!.last().third)) best = cur; cur = ArrayList() }
        val step = v.vh * 0.02f
        for (k in 0 until lm.lat.size - 1) {
            val x0 = v.x(lm.lat[k], lm.lon[k]); val y0 = v.y(lm.lat[k])
            val x1 = v.x(lm.lat[k + 1], lm.lon[k + 1]); val y1 = v.y(lm.lat[k + 1])
            val seg = hypot(x1 - x0, y1 - y0)
            if (seg > v.vw * 3f) { close(); continue }                   // wraps around the far side
            val n = max(1, (seg / step).toInt())
            for (i in (if (k == 0) 0 else 1)..n) {
                val x = x0 + (x1 - x0) * i / n; val y = y0 + (y1 - y0) * i / n
                if (inside(x, y)) {
                    val l = if (cur.isEmpty()) 0f else cur.last().third + hypot(x - cur.last().first, y - cur.last().second)
                    cur.add(Triple(x, y, l))
                } else close()
            }
        }
        close()
        return best
    }

    /** The point [d] px along a run (clamped to its ends). */
    private fun pointAt(run: List<Triple<Float, Float, Float>>, d: Float): Pair<Float, Float> {
        if (d <= 0f) return run[0].first to run[0].second
        for (i in 1 until run.size) {
            val b = run[i]
            if (b.third >= d) {
                val a = run[i - 1]; val t = (d - a.third) / max(b.third - a.third, 1e-3f)
                return (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
            }
        }
        return run.last().first to run.last().second
    }

    private fun styleOf(kind: String): Style? = when (kind) {
        "peak", "pass" -> Style.PEAK
        "low" -> Style.LOW
        "range", "plateau", "desert", "plain", "basin", "peninsula" -> Style.REGION
        "gorge", "valley", "delta", "wetland" -> Style.FEATURE
        "river", "lake", "sea", "strait", "fjord" -> Style.WATER
        "trench", "ridge" -> Style.UNDERSEA
        "reef" -> Style.REEF
        else -> null
    }

    private fun groupOf(kind: String) = when (kind) {
        "peak", "pass", "low" -> "peak"
        "plateau", "desert", "plain", "basin", "peninsula" -> "region"
        "gorge", "valley", "delta", "wetland" -> "feature"
        "sea", "strait", "fjord" -> "sea"
        else -> kind
    }

    private val pens = HashMap<Pair<Style, Float>, MapText.Pen>()
    private fun pen(style: Style, size: Float) = pens.getOrPut(style to size) {
        MapText.Pen(context, size, italic = style.italic, caps = style.caps, tracking = style.tracking)
    }

    // ---- GL thread -----------------------------------------------------------------------------

    /** Compile the quad shader (call from onSurfaceCreated). */
    fun initGl(link: (String, String) -> Int) { layer.initGl(link); current = emptyList() }

    /** Make [set] the names drawn from now on. */
    fun show(set: Batch) {
        current = set.labels
        set.atlas?.let { layer.upload(it) }
        set.atlas = null
    }

    /**
     * Draw the names for a view centred on ([lat], [lon]) with half-height [hh] (radians). [reveal] maps a height
     * to how far the sweep has revealed it (0..1).
     */
    fun draw(lat: Float, lon: Float, hh: Float, vw: Int, vh: Int, alpha: Float,
             places: Boolean, placeBright: Float, landmarks: Boolean, landmarkBright: Float, reveal: (Float) -> Float) {
        if (current.isEmpty() || alpha <= 0f) return
        val v = View(lat, lon, hh, vw, vh)
        layer.begin()
        for (l in current) {
            if (if (l.place) !places else !landmarks) continue
            val a = alpha * reveal(l.revealH)
            if (a <= 0f) continue
            layer.quad(l.g, v.x(l.lat, l.lon), v.y(l.lat), a, vw, vh, scale(l.rgb, if (l.place) placeBright else landmarkBright), l.angle)
        }
        layer.draw(1f)
    }

    private fun scale(rgb: Int, k: Float): Int {
        fun ch(s: Int) = ((rgb shr s and 255) * k).roundToInt().coerceIn(0, 255) shl s
        return ch(16) or ch(8) or ch(0)
    }

    companion object {
        private const val TAG = "MapLabels"
        private const val PPD = 4                       // place grid cells per degree (tools/earth_names.py)
        private const val W = 360 * PPD
        private const val H = 180 * PPD
        private const val GRID_ROWS = 54                // place samples down the view
        private const val MIN_SHARE = 0.05f             // share of the view a country / sea needs to be named
        private const val MIN_AREA = 0.04f              // smallest named area: radius as a fraction of the view height
        private const val REPEAT_PENALTY = 1.5f         // ranks added per name of the same kind already chosen
        const val MAX_PLACES = 8
        const val MAX_LANDMARKS = 20
        private val AREA_KINDS = setOf("range", "plateau", "desert", "plain", "basin", "peninsula", "gorge", "valley",
            "delta", "wetland", "lake", "sea", "strait", "fjord")
        private val KIND_ORDER = listOf("trench", "ridge", "range", "peak", "river", "lake", "reef", "sea", "region", "feature")
        private const val DEG = (PI / 180.0).toFloat()
        private const val HALF_PI = (PI / 2).toFloat()
        private const val TWO_PI = (2.0 * PI).toFloat()

        private fun wrapPi(a: Float): Float {
            var d = a % TWO_PI
            if (d > PI) d -= TWO_PI else if (d < -PI) d += TWO_PI
            return d
        }
    }
}
