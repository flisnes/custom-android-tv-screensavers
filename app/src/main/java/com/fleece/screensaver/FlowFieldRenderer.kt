package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flow-field screensaver: thousands of glowing particles drift along a slowly-evolving vector
 * field (smooth layered sinusoids), leaving aurora-like ribbons via the trail buffer. OLED-safe:
 * dark background, sparse moving glints, no per-frame allocation.
 */
class FlowFieldRenderer : GLSurfaceView.Renderer {
    private val n = 2600
    private val px = FloatArray(n)   // field space [0,1], y down
    private val py = FloatArray(n)
    private val age = IntArray(n)
    private val life = IntArray(n)
    private val col = IntArray(n)

    private val pointBuf = GlUtil.directFloats(n * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var w = 1f; private var h = 1f
    private var lastNanos = 0L
    private var t = 0.0

    // Aurora palette (rgb 0..1).
    private val palette = arrayOf(
        floatArrayOf(0.20f, 0.85f, 0.75f),  // teal
        floatArrayOf(0.25f, 0.70f, 1.00f),  // sky blue
        floatArrayOf(0.45f, 0.95f, 0.55f),  // green
        floatArrayOf(0.65f, 0.45f, 1.00f),  // violet
        floatArrayOf(0.30f, 0.95f, 0.95f),  // cyan
    )

    init { for (i in 0 until n) spawn(i, fresh = true) }

    private fun rnd() = Math.random().toFloat()
    private fun spawn(i: Int, fresh: Boolean) {
        px[i] = rnd(); py[i] = rnd()
        life[i] = 220 + (rnd() * 320).toInt()
        age[i] = if (fresh) (rnd() * life[i]).toInt() else 0
        col[i] = (rnd() * palette.size).toInt().coerceAtMost(palette.size - 1)
    }

    // Smooth, slowly-evolving angle field. x,y in [0,1], t in seconds.
    private fun field(x: Float, y: Float): Float {
        val ts = t.toFloat()
        return (Math.PI.toFloat()) * (
            sin(x * 3.0f + ts * 0.20f) +
            cos(y * 3.2f - ts * 0.16f) +
            0.7f * sin((x + y) * 5.0f + ts * 0.12f)
        )
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
        w = width.toFloat(); h = height.toFloat()
        trail.resize(width, height, 0.045f)   // long, flowing trails
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos == 0L) lastNanos = now
        var dt = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        if (dt > 0.05) dt = 0.05
        t += dt

        pointBuf.clear()
        var count = 0
        val speed = 0.0017f
        for (i in 0 until n) {
            val ang = field(px[i], py[i])
            px[i] += cos(ang) * speed
            py[i] += sin(ang) * speed
            age[i]++
            if (age[i] > life[i] || px[i] < -0.05f || px[i] > 1.05f || py[i] < -0.05f || py[i] > 1.05f) {
                spawn(i, fresh = false)
            }
            // fade in/out over lifetime so respawns don't pop
            val lf = age[i].toFloat() / life[i]
            val env = if (lf < 0.1f) lf / 0.1f else if (lf > 0.85f) (1f - lf) / 0.15f else 1f
            val c = palette[col[i]]
            val ndcX = px[i] * 2f - 1f
            val ndcY = 1f - py[i] * 2f
            pointBuf.put(ndcX).put(ndcY)
            pointBuf.put(c[0]).put(c[1]).put(c[2]).put(0.26f * env)
            pointBuf.put(3.0f)
            count++
        }
        pointBuf.position(0)

        trail.beginFrame()
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive
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
