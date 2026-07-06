package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * Embers rising from an unseen fire below the screen: sparse sparks ride turbulent convection —
 * their rise speed surges and slackens with gusts of hot air rather than being constant, and the
 * speed spread is wide (the big bright sparks are the fast ones; heavy cooling flakes dawdle).
 * They flicker and cool from warm gold through orange to deep red before dying out. Very short
 * additive trails keep them reading as sparks, not wisps. Everything rises — OLED-safe.
 */
class EmbersRenderer : GLSurfaceView.Renderer {
    private val n = 1100

    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vy0 = FloatArray(n)
    private val age = FloatArray(n)
    private val life = FloatArray(n)
    private val phase = FloatArray(n)
    private val flk = FloatArray(n)
    private val big = BooleanArray(n)

    private val pointBuf = GlUtil.directFloats(n * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var hPx = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        for (i in 0 until n) {
            respawn(i)
            y[i] = rnd() * 1.1f            // pre-seed across the screen
            age[i] = rnd() * life[i]
        }
    }

    private fun rnd() = Math.random().toFloat()

    private fun respawn(i: Int) {
        y[i] = 1.02f + rnd() * 0.08f
        x[i] = rnd()
        big[i] = rnd() < 0.07f
        // wide speed spread; the big bright sparks ride the plume fastest
        vy0[i] = if (big[i]) -(0.30f + 0.20f * rnd()) else -(0.09f + 0.20f * rnd())
        life[i] = 5f + rnd() * 5f
        age[i] = 0f
        phase[i] = rnd() * 6.2831855f
        flk[i] = 4f + rnd() * 5f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        pointProg = GlUtil.link(GlUtil.POINT_VS, GlUtil.POINT_FS)
        pPos = GLES20.glGetAttribLocation(pointProg, "aPos")
        pColor = GLES20.glGetAttribLocation(pointProg, "aColor")
        pSize = GLES20.glGetAttribLocation(pointProg, "aSize")
        trail.init()
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        hPx = height.toFloat()
        trail.resize(width, height, 0.24f)   // very short heads: sparks, not wisps
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos == 0L) lastNanos = now
        var dt = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        if (dt > 0.05) dt = 0.05
        t += dt
        val dtf = dt.toFloat()
        val ts = t.toFloat()

        pointBuf.clear()
        var count = 0
        for (i in 0 until n) {
            var h = (1f - age[i] / life[i]).coerceIn(0f, 1f)
            val vx = 0.030f * sin(ts * 0.7f + phase[i]) + 0.025f * sin(y[i] * 6f - ts * 0.9f + phase[i] * 3f)
            // convection is gusty: rise speed surges and slackens, and cooling embers slow down
            val surge = 0.65f + 0.35f * sin(ts * 1.4f + phase[i] * 2f)
            y[i] += vy0[i] * (0.4f + 0.6f * h) * surge * dtf
            x[i] += vx * dtf
            age[i] += dtf
            if (y[i] < -0.03f || age[i] > life[i]) respawn(i)

            h = (1f - age[i] / life[i]).coerceIn(0f, 1f)
            val flicker = 0.72f + 0.28f * sin(ts * flk[i] + phase[i] * 7f)
            val alpha = (0.20f + 0.70f * h) * flicker * (if (big[i]) 1.5f else 1f)
            // warm gold -> orange -> deep red as the ember cools
            val r: Float; val g: Float; val b: Float
            if (h > 0.6f) {
                val f = (h - 0.6f) / 0.4f
                r = 1.0f; g = 0.55f + (0.78f - 0.55f) * f; b = 0.16f + (0.38f - 0.16f) * f
            } else {
                val f = h / 0.6f
                r = 0.60f + (1.0f - 0.60f) * f; g = 0.13f + (0.55f - 0.13f) * f; b = 0.03f + (0.16f - 0.03f) * f
            }
            pointBuf.put(x[i] * 2f - 1f).put(1f - y[i] * 2f)
            pointBuf.put(r).put(g).put(b).put(alpha)
            pointBuf.put((1.6f + 3.4f * h) * (if (big[i]) 2f else 1f) * hPx / 720f)
            count++
        }
        pointBuf.position(0)

        trail.beginFrame()
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        drawPoints(count)
        trail.blit()
    }

    private fun drawPoints(count: Int) {
        if (count <= 0) return
        GLES20.glUseProgram(pointProg)
        pointBuf.position(0)
        GLES20.glEnableVertexAttribArray(pPos)
        GLES20.glVertexAttribPointer(pPos, 2, GLES20.GL_FLOAT, false, 28, pointBuf)
        pointBuf.position(2)
        GLES20.glEnableVertexAttribArray(pColor)
        GLES20.glVertexAttribPointer(pColor, 4, GLES20.GL_FLOAT, false, 28, pointBuf)
        pointBuf.position(6)
        GLES20.glEnableVertexAttribArray(pSize)
        GLES20.glVertexAttribPointer(pSize, 1, GLES20.GL_FLOAT, false, 28, pointBuf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
    }
}
