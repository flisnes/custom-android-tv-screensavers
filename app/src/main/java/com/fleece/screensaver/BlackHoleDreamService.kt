package com.fleece.screensaver

import android.opengl.GLSurfaceView
import android.provider.Settings
import android.service.dreams.DreamService

/**
 * OLED-friendly screensaver. Each time the dream activates it picks a random GL scene from
 * the enabled subset of [scenes]. The panel is dimmed while dreaming and the GL thread is
 * paused when the dream stops.
 *
 * Optional override: `settings put system fleece_scene <index>` pins one scene (0=black hole,
 * 1=flow field, 2=wheat field, 3=murmuration, 4=vortex ocean, 5=galaxy, 6=ink, 7=embers);
 * any other value (or unset) = random each activation. Note the override can still pin scenes
 * that are excluded from the random pool.
 */
class BlackHoleDreamService : DreamService() {

    private var glView: GLSurfaceView? = null

    private val scenes = listOf<() -> GLSurfaceView.Renderer>(
        { BlackHoleRenderer() },
        { FlowFieldRenderer() },
        { WheatFieldRenderer() },
        { MurmurationRenderer() },
        { VortexRenderer() },
        { GalaxyRenderer() },
        { InkRenderer() },
        { EmbersRenderer() },
    )

    // Indices eligible for random selection. Wheat field (2) is disabled from the random pool
    // but can still be pinned via the fleece_scene override.
    private val randomPool = listOf(0, 1, 3, 4, 5, 6, 7)

    private fun chooseRenderer(): GLSurfaceView.Renderer {
        val forced = runCatching {
            Settings.System.getInt(contentResolver, "fleece_scene", -1)
        }.getOrDefault(-1)
        val idx = if (forced in scenes.indices) forced else randomPool.random()
        return scenes[idx]()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        // Dim the OLED panel while dreaming (burn-in mitigation).
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 0.45f } }

        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(chooseRenderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView = view
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        glView?.onResume()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        glView?.onPause()
    }

    override fun onDetachedFromWindow() {
        glView = null
        super.onDetachedFromWindow()
    }
}
