package com.fleece.screensaver

import android.content.Context
import android.opengl.GLSurfaceView

/**
 * Every scene the app can show. The index is the value accepted by the `fleece_scene` adb override,
 * so new scenes must be appended, never inserted. [key] is the stable id stored in preferences.
 */
class SceneInfo(
    val key: String,
    val title: String,
    val description: String,
    val glVersion: Int = 2,
    val defaultEnabled: Boolean = true,
    val make: (Context) -> GLSurfaceView.Renderer,
)

object SceneCatalog {
    val scenes: List<SceneInfo> = listOf(
        SceneInfo("black_hole", "Black hole", "Glowing particles swirling into a black hole") { BlackHoleRenderer() },
        SceneInfo("flow_field", "Flow field", "Particles drifting along a slowly evolving current, leaving aurora-like ribbons") { FlowFieldRenderer() },
        SceneInfo("wheat_field", "Wheat field", "Gusts of wind travelling across a golden wheat field", defaultEnabled = false) { WheatFieldRenderer() },
        SceneInfo("murmuration", "Murmuration", "A flock of starlings folding around a wandering attractor (boids)", defaultEnabled = false) { MurmurationRenderer() },
        SceneInfo("vortex_ocean", "Vortex ocean", "Glowing spirals around drifting whirlpools that fade and reverse", defaultEnabled = false) { VortexRenderer() },
        SceneInfo("galaxy", "Galaxy", "A tilted spiral galaxy turning slowly", defaultEnabled = false) { GalaxyRenderer() },
        SceneInfo("ink", "Ink in water", "Coloured ink drops bursting and dispersing in water", defaultEnabled = false) { InkRenderer() },
        SceneInfo("embers", "Embers", "Sparks rising from an unseen fire") { EmbersRenderer() },
        SceneInfo("moon_zoom", "Moon close-up", "Contour sweep over a random patch of the Moon", glVersion = 3) {
            MoonRenderer(it, MoonRenderer.Mode.ZOOM)
        },
        SceneInfo("moon_globe", "Moon globe", "Spinning Moon with a continuous contour sweep", glVersion = 3) {
            MoonRenderer(it, MoonRenderer.Mode.GLOBE)
        },
        SceneInfo("moon_lit", "Lit Moon globe", "Spinning Moon, only contours facing the light are drawn", glVersion = 3) {
            MoonRenderer(it, MoonRenderer.Mode.LIT)
        },
    )

    fun indexOf(key: String): Int = scenes.indexOfFirst { it.key == key }
}

/** Which scenes are in the random rotation. Stored as a set of [SceneInfo.key]s. */
class ScenePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fleece", Context.MODE_PRIVATE)

    /** Enabled scene keys; the catalog defaults until the user has saved a choice. */
    fun enabledKeys(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, null)?.toSet()
            ?: SceneCatalog.scenes.filter { it.defaultEnabled }.map { it.key }.toSet()

    fun isEnabled(key: String) = key in enabledKeys()

    fun setEnabled(key: String, enabled: Boolean) {
        val next = enabledKeys().toMutableSet().apply { if (enabled) add(key) else remove(key) }
        prefs.edit().putStringSet(KEY_ENABLED, next).apply()
    }

    fun resetToDefaults() {
        prefs.edit().remove(KEY_ENABLED).apply()
    }

    /** Catalog indices eligible for random selection; falls back to the defaults if none is enabled. */
    fun rotationPool(): List<Int> {
        val keys = enabledKeys()
        val pool = SceneCatalog.scenes.indices.filter { SceneCatalog.scenes[it].key in keys }
        return pool.ifEmpty { SceneCatalog.scenes.indices.filter { SceneCatalog.scenes[it].defaultEnabled } }
    }

    private companion object {
        const val KEY_ENABLED = "enabled_scenes"
    }
}
