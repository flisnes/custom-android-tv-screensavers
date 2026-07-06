package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/**
 * Ink drops in water: every ~9s a drop of a new colour lands somewhere and blooms — particles get
 * a decaying radial burst, then churn in a fine, fast-evolving divergence-free field (curl of
 * high-frequency sines) with a touch of diffusion jitter and buoyancy. Big soft points and a slow
 * trail fade make the clouds billow and dissolve into each other. Clouds keep drifting and
 * dissolving, so nothing is static — OLED-safe.
 */
class InkRenderer : GLSurfaceView.Renderer {
    private val n = 2000
    private val dropPeriod = 9.0f

    private val posX = FloatArray(n); private val posY = FloatArray(n)
    private val velX = FloatArray(n); private val velY = FloatArray(n)
    private val age = FloatArray(n)
    private val life = FloatArray(n)
    private val cr = FloatArray(n); private val cg = FloatArray(n); private val cb = FloatArray(n)

    private val palette = arrayOf(
        floatArrayOf(0.85f, 0.20f, 0.75f),   // magenta
        floatArrayOf(0.20f, 0.60f, 1.00f),   // azure
        floatArrayOf(0.30f, 0.90f, 0.80f),   // turquoise
        floatArrayOf(0.60f, 0.35f, 1.00f),   // violet
        floatArrayOf(0.95f, 0.50f, 0.25f),   // amber
    )

    private val jrnd = java.util.Random()
    private val pointBuf = GlUtil.directFloats(n * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var hPx = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        for (i in 0 until n) {
            age[i] = rnd() * 18f
            life[i] = 12f + rnd() * 6f
            val di = kotlin.math.floor(-age[i] / dropPeriod).toInt()   // the past drop it came from
            val e = emitterPos(di)
            posX[i] = e[0] + gauss() * (0.012f + age[i] * 0.008f)
            posY[i] = e[1] + gauss() * (0.012f + age[i] * 0.008f)
            val c = emitterColor(di)
            cr[i] = c[0]; cg[i] = c[1]; cb[i] = c[2]
        }
    }

    private fun rnd() = Math.random().toFloat()
    private fun gauss() = (rnd() + rnd() + rnd() + rnd() - 2f) * 0.87f

    /** Deterministic per-drop landing point (same trick as the Python prototype). */
    private fun emitterPos(idx: Int): FloatArray {
        val rs = java.util.Random(1000L + idx)
        return floatArrayOf(0.2f + 0.6f * rs.nextFloat(), 0.22f + 0.56f * rs.nextFloat())
    }

    private fun emitterColor(idx: Int): FloatArray = palette[((idx % palette.size) + palette.size) % palette.size]

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
        trail.resize(width, height, 0.02f)   // slow fade: clouds linger and billow
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

        val dropIdx = (ts / dropPeriod).toInt()
        val e = emitterPos(dropIdx)
        val ec = emitterColor(dropIdx)
        val burstDecay = exp(-dtf / 1.5f)
        val jit = 0.0015f * kotlin.math.sqrt(dtf * 60f)   // frame-rate-independent-ish jitter

        pointBuf.clear()
        var count = 0
        for (i in 0 until n) {
            // fine, fast-evolving local churn: curl of three high-frequency sine potentials
            val c1 = cos(13.0f * posX[i] + 17.0f * posY[i] + 0.7f * ts)
            val c2 = cos(23.0f * posX[i] + 19.0f * posY[i] + 1.1f * ts + 2.1f)
            val c3 = cos(31.0f * posX[i] + 29.0f * posY[i] + 1.6f * ts + 4.4f)
            val vx = 0.0011f * 17.0f * c1 + 0.0007f * 19.0f * c2 + 0.0004f * 29.0f * c3
            val vy = -(0.0011f * 13.0f * c1 + 0.0007f * 23.0f * c2 + 0.0004f * 31.0f * c3)
            velX[i] *= burstDecay
            velY[i] *= burstDecay
            posX[i] += (vx + velX[i]) * dtf + gauss() * jit
            posY[i] += (vy + velY[i] + 0.004f) * dtf + gauss() * jit
            age[i] += dtf
            if (age[i] > life[i]) {
                posX[i] = e[0] + gauss() * 0.010f
                posY[i] = e[1] + gauss() * 0.010f
                velX[i] = gauss() * 0.045f
                velY[i] = gauss() * 0.045f
                age[i] = 0f
                life[i] = 12f + rnd() * 6f
                cr[i] = ec[0]; cg[i] = ec[1]; cb[i] = ec[2]
            }
            val lf = age[i] / life[i]
            val env = min(age[i], 1f) * min((1f - lf) / 0.3f, 1f)
            pointBuf.put(posX[i] * 2f - 1f).put(1f - posY[i] * 2f)
            pointBuf.put(cr[i]).put(cg[i]).put(cb[i]).put(0.05f * env)
            pointBuf.put((4f + 9f * lf) * hPx / 720f)
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
