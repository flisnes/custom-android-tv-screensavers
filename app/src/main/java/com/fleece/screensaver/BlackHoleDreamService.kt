package com.fleece.screensaver

import android.opengl.GLSurfaceView
import android.service.dreams.DreamService

/**
 * OLED-friendly screensaver: a black-hole accretion simulation rendered with OpenGL ES
 * ([BlackHoleRenderer]). The panel is dimmed while dreaming, and the GL thread is paused when
 * the dream stops.
 */
class BlackHoleDreamService : DreamService() {

    private var glView: GLSurfaceView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        // Dim the OLED panel while dreaming (burn-in mitigation).
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 0.45f } }

        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(BlackHoleRenderer())
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
