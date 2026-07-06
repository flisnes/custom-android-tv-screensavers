package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Spiral galaxy: a warm gaussian bulge plus two blue log-spiral arms of stars, seen tilted.
 * Stars orbit (near-rigid in the disk, faster in the bulge) and have finite lifetimes; respawns
 * land back on the arm skeleton, so the arms rotate without ever winding up. Star twinkle plus
 * a slowly wandering, slowly precessing centre keeps every pixel moving — OLED-safe.
 */
class GalaxyRenderer : GLSurfaceView.Renderer {
    private val n = 3600
    private val nBulge = (n * 0.28f).toInt()
    private val tilt = 0.52f

    private val r = FloatArray(n)
    private val th = FloatArray(n)
    private val age = FloatArray(n)
    private val life = FloatArray(n)
    private val phase = FloatArray(n)
    private val cr = FloatArray(n); private val cg = FloatArray(n); private val cb = FloatArray(n)

    private val pointBuf = GlUtil.directFloats(n * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var hPx = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        for (i in 0 until n) { seed(i); age[i] = rnd() * life[i] }
    }

    private fun rnd() = Math.random().toFloat()
    private fun gauss() = (rnd() + rnd() + rnd() + rnd() - 2f) * 0.87f
    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)

    private fun seed(i: Int) {
        phase[i] = rnd() * 6.2831855f
        life[i] = 20f + rnd() * 25f
        age[i] = 0f
        if (i < nBulge) {
            // warm bulge blob
            r[i] = kotlin.math.abs(gauss()) * 0.055f + 0.004f
            th[i] = rnd() * 6.2831855f
            cr[i] = clamp01(1.00f + (rnd() - 0.5f) * 0.10f)
            cg[i] = clamp01(0.86f + (rnd() - 0.5f) * 0.10f)
            cb[i] = clamp01(0.62f + (rnd() - 0.5f) * 0.10f)
        } else {
            // two log-spiral arms with gaussian spread, warm inner -> blue outer
            val ra = 0.06f + 0.40f * Math.pow(rnd().toDouble(), 1.1).toFloat()
            val arm = if (rnd() < 0.5f) 0f else Math.PI.toFloat()
            r[i] = ra
            th[i] = arm + ln(ra / 0.06f) * 3.4f + gauss() * 0.22f
            val f = ((ra - 0.06f) / 0.40f).coerceIn(0f, 1f)
            cr[i] = clamp01(1.00f + (0.50f - 1.00f) * f + (rnd() - 0.5f) * 0.10f)
            cg[i] = clamp01(0.88f + (0.68f - 0.88f) * f + (rnd() - 0.5f) * 0.10f)
            cb[i] = clamp01(0.70f + (1.00f - 0.70f) * f + (rnd() - 0.5f) * 0.10f)
        }
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
        trail.resize(width, height, 0.045f)
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

        val cx = 0.5f + 0.035f * sin(0.043f * ts)
        val cy = 0.5f + 0.030f * sin(0.031f * ts + 1.0f)
        val psi = 0.02f * ts + 0.4f     // slow precession of the whole disk
        val cp = cos(psi); val sp = sin(psi)
        val size = hPx * 2.6f / 720f

        pointBuf.clear()
        var count = 0
        for (i in 0 until n) {
            val om = if (i < nBulge) {
                min(0.020f / Math.pow((r[i] + 0.04f).toDouble(), 1.5).toFloat(), 1.2f)
            } else {
                0.055f + 0.020f / (r[i] + 0.08f)
            }
            th[i] += om * dtf
            age[i] += dtf
            if (age[i] > life[i]) seed(i)   // respawn on the arm skeleton: arms never wind up

            val x = r[i] * cos(th[i])
            val y = r[i] * sin(th[i]) * tilt
            val xr = x * cp - y * sp
            val yr = x * sp + y * cp
            val sx = cx + xr
            val sy = cy + yr * 1.55f
            val lf = age[i] / life[i]
            val env = min(lf / 0.12f, 1f) * min((1f - lf) / 0.15f, 1f)
            val twinkle = 0.75f + 0.25f * sin(2.0f * ts + phase[i])
            pointBuf.put(sx * 2f - 1f).put(1f - sy * 2f)
            pointBuf.put(cr[i]).put(cg[i]).put(cb[i]).put(0.22f * env * twinkle)
            pointBuf.put(size)
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
