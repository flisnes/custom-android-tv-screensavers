package com.fleece.screensaver

import android.annotation.SuppressLint
import android.graphics.Color
import android.service.dreams.DreamService
import android.webkit.WebView

/**
 * Custom OLED-friendly screensaver: a black-hole accretion simulation rendered by a Canvas
 * page hosted in a WebView (the "fast path" from the handover). The panel is dimmed while
 * dreaming, and the animation is paused when the dream stops to save power.
 */
class BlackHoleDreamService : DreamService() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        // Dim the OLED panel while dreaming (burn-in mitigation).
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 0.45f } }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            loadUrl("file:///android_asset/screensaver.html")
        }
        setContentView(web)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        web?.onResume()
        web?.resumeTimers()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        // Pause the animation when the dream ends to save power.
        web?.onPause()
        web?.pauseTimers()
    }

    override fun onDetachedFromWindow() {
        web?.destroy()
        web = null
        super.onDetachedFromWindow()
    }
}
