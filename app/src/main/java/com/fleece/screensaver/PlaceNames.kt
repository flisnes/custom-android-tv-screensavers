package com.fleece.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor

/**
 * The name of the country (or, over open water, the sea) the Earth close-up ([MoonRenderer] ZOOM) is looking
 * at, shown at the bottom centre. The lookup grid is assets/earth/places.grid with names in places.tsv
 * (Natural Earth, see tools/earth_names.py). [build] samples the view and names the country covering most of
 * it (weighted toward the middle of the view), or the most-covered sea if there is little land in view; countries are set in spaced capitals, seas in
 * spaced italic capitals (map convention).
 */
class PlaceNames(private val context: Context) {

    class Batch internal constructor(internal val glyphs: MapText.Glyphs?, internal var atlas: Bitmap?)

    private class Places(val land: BooleanArray, val names: List<String>, val grid: ShortArray)

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

    private val layer = MapText.Layer(1)
    private var current: MapText.Glyphs? = null

    /** Names the view centred on ([lat], [lon]) radians with half-height [hh] radians; [sizePx] = text size in px. */
    fun build(lat: Float, lon: Float, hh: Float, vw: Int, vh: Int, sizePx: Float): Batch {
        val p = places ?: return Batch(null, null)
        val score = FloatArray(p.names.size)
        val aspect = vw.toFloat() / vh
        var total = 0f
        for (j in 0 until SAMPLES) for (i in 0 until SAMPLES) {
            // moon.frag's zoom projection: screen (-aspect..aspect, -1..1) -> (lat, lon)
            val sy = (j + 0.5f) / SAMPLES * 2f - 1f; val sx = ((i + 0.5f) / SAMPLES * 2f - 1f) * aspect
            val la = lat + sy * hh
            val lo = lon + sx * hh / cos(la)
            val y = floor((90f - la * RAD2DEG) * PPD).toInt().coerceIn(0, H - 1)
            val x = Math.floorMod(floor(lo * RAD2DEG * PPD).toInt(), W)
            val wgt = exp(-(sx * sx + sy * sy) / (2f * CENTRE_SIGMA * CENTRE_SIGMA))   // the middle of the view counts most
            score[p.grid[y * W + x].toInt() and 0xffff] += wgt; total += wgt
        }
        val landScore = score.indices.filter { p.land[it] }.sumOf { score[it].toDouble() }
        val best = score.indices.filter { it > 0 && p.land[it] == (landScore >= MIN_LAND * total) }.maxByOrNull { score[it] }
        if (best == null || score[best] == 0f) return Batch(null, null)
        val pen = MapText.Pen(context, sizePx, italic = !p.land[best], caps = true, tracking = 0.22f)
        val (bmp, g) = MapText.atlas(listOf(p.names[best] to pen))
        return Batch(g[0], bmp)
    }

    /** GL thread: compile the quad shader (call from onSurfaceCreated). */
    fun initGl(link: (String, String) -> Int) { layer.initGl(link); current = null }

    /** GL thread: make [set] the name drawn from now on. */
    fun show(set: Batch) {
        current = set.glyphs
        set.atlas?.let { layer.upload(it) }
        set.atlas = null
    }

    /** GL thread: draw the name at the bottom centre. */
    fun draw(vw: Int, vh: Int, alpha: Float, bright: Float) {
        val g = current ?: return
        layer.begin()
        layer.quad(g, vw / 2f, BOTTOM * vh + g.h / 2, alpha, vw, vh)
        layer.draw(bright)
    }

    companion object {
        private const val TAG = "PlaceNames"
        private const val PPD = 4                       // grid cells per degree (tools/earth_names.py)
        private const val W = 360 * PPD
        private const val H = 180 * PPD
        private const val SAMPLES = 32                  // samples across the view (each way)
        private const val MIN_LAND = 0.12f              // (weighted) land share of the view above which a country is named
        private const val CENTRE_SIGMA = 0.6f           // sample weight falloff, in half screen heights from the centre
        private const val BOTTOM = 0.07f                // gap below the name, as a fraction of the screen height
        private const val RAD2DEG = (180.0 / PI).toFloat()
    }
}
