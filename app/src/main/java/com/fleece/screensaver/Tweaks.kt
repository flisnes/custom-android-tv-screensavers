package com.fleece.screensaver

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One adjustable value of a scene, shown in the preview's tweak panel. A choice parameter stores
 * the index into [choices]. [restart] marks values that only take effect on a fresh renderer
 * (e.g. particle counts); the preview restarts the scene when one of them changes.
 */
class Param(
    val key: String,
    val label: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    val help: String = "",
    val group: String = "",
    val choices: List<String>? = null,
    val restart: Boolean = false,
    /** A colour stored as its 0xRRGGBB value (exact in a float); Left/Right steps through [Palette]. */
    val color: Boolean = false,
) {
    fun clamp(v: Float): Float {
        val c = v.coerceIn(min, max)
        // Snap to the step grid so repeated presses do not accumulate float noise.
        return if (choices != null) c.roundToInt().toFloat() else ((c - min) / step).roundToInt() * step + min
    }

    fun format(v: Float): String {
        choices?.let { return it.getOrElse(v.roundToInt()) { v.toString() } }
        if (color) return "#%06X".format(v.roundToInt() and 0xFFFFFF)
        val decimals = when {
            step >= 1f -> 0
            step >= 0.1f -> 1
            step >= 0.01f -> 2
            else -> 3
        }
        return "%.${decimals}f".format(java.util.Locale.US, v)
    }

    fun isDefault(v: Float) = abs(v - default) < step * 0.01f
}

fun choiceParam(key: String, label: String, default: Int, choices: List<String>, help: String = "", group: String = "", restart: Boolean = false) =
    Param(key, label, default.toFloat(), 0f, (choices.size - 1).toFloat(), 1f, help, group, choices, restart)

fun toggleParam(key: String, label: String, default: Boolean, help: String = "", group: String = "") =
    choiceParam(key, label, if (default) 1 else 0, listOf("Off", "On"), help, group)

fun colorParam(key: String, label: String, default: Int, help: String = "", group: String = "") =
    Param(key, label, default.toFloat(), 0f, 0xFFFFFF.toFloat(), 1f, help, group, color = true)

/** The colours a colour tweak steps through: each hue from light to dark, hue by hue, then the greys. */
object Palette {
    val colors: IntArray = run {
        val out = ArrayList<Int>()
        val tones = listOf(0.30f to 1f, 0.55f to 0.95f, 0.8f to 0.8f, 0.9f to 0.55f, 0.95f to 0.32f, 0.95f to 0.16f)   // (saturation, value)
        for (h in 0 until 360 step 30) for ((s, v) in tones)
            out += android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s, v)) and 0xFFFFFF
        out += listOf(0xFFFFFF, 0xC8C8C8, 0x909090, 0x585858, 0x282828, 0x000000)
        out.toIntArray()
    }

    /** The colour one step in [dir] from [rgb]; a colour not in the palette (a default) first moves to its nearest. */
    fun step(rgb: Int, dir: Int): Int {
        fun d(a: Int, b: Int): Int {
            val dr = (a shr 16 and 255) - (b shr 16 and 255); val dg = (a shr 8 and 255) - (b shr 8 and 255); val db = (a and 255) - (b and 255)
            return dr * dr + dg * dg + db * db
        }
        val i = colors.indices.minBy { d(colors[it], rgb) }
        return if (colors[i] != rgb) colors[i] else colors[Math.floorMod(i + dir, colors.size)]
    }
}

/**
 * The current values of a scene's parameters. Written by the UI thread (tweak panel), read by the
 * GL thread every frame, so the map is replaced rather than mutated.
 */
class Tweaks(val params: List<Param>, initial: Map<String, Float> = emptyMap()) {
    private val byKey = params.associateBy { it.key }

    @Volatile
    private var values: Map<String, Float> = params.associate { it.key to (initial[it.key]?.let(it::clamp) ?: it.default) }

    operator fun get(key: String): Float = values[key] ?: error("unknown tweak '$key'")
    fun int(key: String): Int = get(key).roundToInt()
    fun bool(key: String): Boolean = get(key) >= 0.5f

    fun param(key: String): Param = byKey.getValue(key)

    /** Sets [key] (clamped and snapped) and returns the stored value. */
    fun set(key: String, v: Float): Float {
        val c = param(key).clamp(v)
        values = values + (key to c)
        return c
    }

    fun changed(): Map<String, Float> = params.filter { !it.isDefault(get(it.key)) }.associate { it.key to get(it.key) }

    /** "key=value key=value" of the values that differ from the defaults (logged and shown). */
    fun describeChanged(): String =
        changed().entries.joinToString(" ") { (k, v) -> "$k=${param(k).format(v)}" }.ifEmpty { "(all defaults)" }
}

/**
 * Persists tweaks in the "fleece_tweaks" preferences as floats named `<sceneKey>.<paramKey>`.
 * Only values that differ from the default are stored, so the file is a readable list of what was
 * changed: `adb shell run-as com.fleece.screensaver cat shared_prefs/fleece_tweaks.xml`.
 */
class TweakStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fleece_tweaks", Context.MODE_PRIVATE)

    fun load(scene: SceneInfo): Tweaks {
        val stored = scene.params.mapNotNull { p ->
            val k = "${scene.key}.${p.key}"
            if (prefs.contains(k)) p.key to prefs.getFloat(k, p.default) else null
        }.toMap()
        return Tweaks(scene.params, stored)
    }

    fun save(scene: SceneInfo, key: String, value: Float) {
        val p = scene.params.first { it.key == key }
        val k = "${scene.key}.$key"
        prefs.edit().apply { if (p.isDefault(value)) remove(k) else putFloat(k, value) }.apply()
    }

    fun reset(scene: SceneInfo) {
        prefs.edit().apply { scene.params.forEach { remove("${scene.key}.${it.key}") } }.apply()
    }

    fun changedCount(scene: SceneInfo): Int = scene.params.count { prefs.contains("${scene.key}.${it.key}") }
}

/** Optional live readout for the preview's info overlay (called from the UI thread). */
interface SceneStatus {
    fun status(): String
}
