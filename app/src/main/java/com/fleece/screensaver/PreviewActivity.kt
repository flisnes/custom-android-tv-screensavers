package com.fleece.screensaver

import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Runs one scene full screen, exactly as the dream would (same renderer, same dimming), until Back is
 * pressed. An app cannot start the system dream itself, so this is how scenes are previewed.
 */
class PreviewActivity : Activity() {

    private var glView: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = SceneCatalog.scenes.firstOrNull { it.key == intent.getStringExtra(EXTRA_SCENE) }
        if (scene == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Same dimming as the dream, so the preview looks like the real thing.
        window.attributes = window.attributes.apply { screenBrightness = 0.45f }

        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(scene.glVersion)
            setRenderer(scene.make(this@PreviewActivity))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView = view

        val label = TextView(this).apply {
            text = "${scene.title}  ·  Back to return"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFFCCCCCC.toInt())
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(48, 0, 0, 36) })
        })
        // Hide the label after a few seconds so it does not sit on the panel.
        label.postDelayed({
            label.animate().alpha(0f).setDuration(800).withEndAction { label.visibility = View.GONE }
        }, 3000)
        hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        glView?.onPause()
        super.onPause()
    }

    companion object {
        const val EXTRA_SCENE = "scene"
    }
}
