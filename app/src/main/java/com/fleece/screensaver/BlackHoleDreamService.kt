package com.fleece.screensaver

import android.opengl.GLSurfaceView
import android.provider.Settings
import android.service.dreams.DreamService
import android.util.Log

/**
 * OLED-friendly screensaver. Each time the dream activates it picks a random GL scene from the
 * scenes enabled in the Fleece Screensavers app (see [ScenePrefs] and [SettingsActivity]). The panel
 * is dimmed while dreaming and the GL thread is paused when the dream stops.
 *
 * Optional override: `settings put system fleece_scene <index>` pins one scene by its index in
 * [SceneCatalog.scenes], even one that is not enabled; any other value (or unset) = random rotation.
 */
class BlackHoleDreamService : DreamService() {

    private var glView: GLSurfaceView? = null

    private fun chooseScene(): SceneInfo {
        val scenes = SceneCatalog.scenes
        val forced = runCatching {
            Settings.System.getInt(contentResolver, "fleece_scene", -1)
        }.getOrDefault(-1)
        val idx = if (forced in scenes.indices) forced else ScenePrefs(this).rotationPool().random()
        Log.i("FleeceDream", "scene ${scenes[idx].key}" + if (forced in scenes.indices) " (pinned by fleece_scene)" else "")
        return scenes[idx]
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val scene = chooseScene()
        val tweaks = TweakStore(this).load(scene)
        Log.i("FleeceDream", "tweaks ${scene.key}: ${tweaks.describeChanged()}")
        // Dim the OLED panel while dreaming (burn-in mitigation); 0.45 unless tweaked.
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = tweaks["panelBrightness"] } }

        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(scene.glVersion)
            setRenderer(scene.make(this@BlackHoleDreamService, tweaks))
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
