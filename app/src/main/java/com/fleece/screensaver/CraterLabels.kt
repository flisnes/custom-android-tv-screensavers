package com.fleece.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Crater names over the moon close-up ([MoonRenderer] ZOOM). Names come from assets/moon/craters.tsv (IAU
 * nomenclature, see tools/moon_names.py), largest first. For each zoom window [build] picks the biggest
 * craters in view that don't overlap, finds each one's floor in the window's height field and renders the
 * names into one atlas ([MapText], load thread); [draw] places them every frame with the same projection
 * as moon.frag, so they follow the zoom/pan drift, and fades each name in with the contour line at the
 * bottom of its crater.
 */
class CraterLabels(private val context: Context) {

    private class Crater(val name: String, val lat: Float, val lon: Float, val diamKm: Float)   // radians
    internal class Label(val lat: Float, val lon: Float, val floorH: Float, val g: MapText.Glyphs)

    /** The labels of one window, prepared off the GL thread. */
    class Batch internal constructor(internal val labels: List<Label>, internal var atlas: Bitmap?)

    private val craters: List<Crater> by lazy {
        try {
            context.assets.open("moon/craters.tsv").bufferedReader().readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 4) null
                else Crater(p[0], p[1].toFloat() * DEG, p[2].toFloat() * DEG, p[3].toFloat())
            }
        } catch (e: Exception) { Log.e(TAG, "craters.tsv missing", e); emptyList() }
    }

    private val layer = MapText.Layer(MAX_LABELS)
    private var current: List<Label> = emptyList()

    /**
     * Picks and renders the names for a window centred on ([lat], [lon]) radians with half-height [hh] radians.
     * [minFrac]: smallest labelled crater as a fraction of the view height; [sizePx]: text size in screen px.
     * [data] ([w] x [h]) is the window's height field, covering [extract] (lon0, lat0, dlon, dlat as in moon.frag).
     */
    fun build(lat: Float, lon: Float, hh: Float, vw: Int, vh: Int, radiusM: Float,
              maxCount: Int, minFrac: Float, sizePx: Float,
              data: FloatArray, w: Int, h: Int, extract: FloatArray): Batch {
        val viewKm = 2f * hh * radiusM / 1000f
        val pen = MapText.Pen(context, sizePx)
        val margin = 0.06f * vh
        val chosen = ArrayList<Pair<Crater, RectF>>()
        for (c in craters) {                                          // largest first
            if (chosen.size >= maxCount.coerceAtMost(MAX_LABELS) || c.diamKm < minFrac * viewKm) break
            val (sx, sy) = project(c.lat, c.lon, lat, lon, hh, vw, vh)
            if (sx < margin || sx > vw - margin || sy < margin || sy > vh - margin) continue
            val lw = pen.width(c.name); val lh = pen.height
            val r = RectF(sx - lw / 2, sy - lh / 2, sx + lw / 2, sy + lh / 2)
            if (chosen.any { RectF.intersects(it.second, r) }) continue
            chosen += c to r
        }
        if (chosen.isEmpty()) return Batch(emptyList(), null)
        val (bmp, glyphs) = MapText.atlas(chosen.map { it.first.name to pen })
        val labels = chosen.mapIndexed { i, (c, _) ->
            Label(c.lat, c.lon, floorHeight(c, lon, radiusM, data, w, h, extract), glyphs[i])
        }
        return Batch(labels, bmp)
    }

    /** Lowest height within the inner [FLOOR_FRAC] of the crater's diameter (the crater floor). */
    private fun floorHeight(c: Crater, cLon: Float, radiusM: Float, data: FloatArray, w: Int, h: Int, ex: FloatArray): Float {
        val rLat = FLOOR_FRAC * c.diamKm * 1000f / radiusM            // search radius, radians of latitude
        val rLon = rLat / max(cos(c.lat), 0.05f)
        val lon = cLon + wrapPi(c.lon - cLon)                         // same unwrapping as the window extract
        val x0 = max(0, floor((lon - rLon - ex[0]) / ex[2] * w).toInt()); val x1 = min(w - 1, ceil((lon + rLon - ex[0]) / ex[2] * w).toInt())
        val y0 = max(0, floor((ex[1] - c.lat - rLat) / ex[3] * h).toInt()); val y1 = min(h - 1, ceil((ex[1] - c.lat + rLat) / ex[3] * h).toInt())
        var lo = Float.MAX_VALUE
        for (y in y0..y1) for (x in x0..x1) {
            val dy = (ex[1] - (y + 0.5f) / h * ex[3] - c.lat) / rLat
            val dx = (ex[0] + (x + 0.5f) / w * ex[2] - lon) / rLon
            if (dx * dx + dy * dy <= 1f) lo = min(lo, data[y * w + x])
        }
        return if (lo == Float.MAX_VALUE) data[(y0 + y1) / 2 * w + (x0 + x1) / 2] else lo
    }

    /** GL thread: compile the quad shader (call from onSurfaceCreated). */
    fun initGl(link: (String, String) -> Int) {
        layer.initGl(link)
        current = emptyList()
    }

    /** GL thread: make [set] the labels drawn from now on. */
    fun show(set: Batch) {
        current = set.labels
        set.atlas?.let { layer.upload(it) }
        set.atlas = null
    }

    /**
     * GL thread: draw the current names for a view centred on ([lat], [lon]) with half-height [hh] (radians).
     * [reveal] maps a height to how far its contour line has faded in (0..1): a name follows the lowest contour
     * level on its crater floor.
     */
    fun draw(lat: Float, lon: Float, hh: Float, vw: Int, vh: Int, alpha: Float, bright: Float, interval: Float,
             reveal: (Float) -> Float) {
        if (current.isEmpty() || alpha <= 0f) return
        layer.begin()
        for (l in current) {
            val (sx, sy) = project(l.lat, l.lon, lat, lon, hh, vw, vh)
            layer.quad(l.g, sx, sy, alpha * reveal(ceil(l.floorH / interval) * interval), vw, vh)
        }
        layer.draw(bright)
    }

    /** moon.frag's zoom projection, inverted: (lat, lon) -> screen px (y up). */
    private fun project(lat: Float, lon: Float, cLat: Float, cLon: Float, hh: Float, vw: Int, vh: Int): Pair<Float, Float> {
        val sx = wrapPi(lon - cLon) * cos(lat) / hh
        val sy = (lat - cLat) / hh
        return (vw / 2f + sx * vh / 2f) to (vh / 2f + sy * vh / 2f)
    }

    private fun wrapPi(a: Float): Float {
        var d = a % TWO_PI
        if (d > PI) d -= TWO_PI else if (d < -PI) d += TWO_PI
        return d
    }

    companion object {
        private const val TAG = "CraterLabels"
        private const val DEG = (PI / 180.0).toFloat()
        private const val TWO_PI = (2.0 * PI).toFloat()
        private const val FLOOR_FRAC = 0.3f     // floor search radius as a fraction of the diameter (inside the rim)
        const val MAX_LABELS = 30
    }
}
