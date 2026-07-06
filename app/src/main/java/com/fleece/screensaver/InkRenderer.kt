package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Ink drops in water. Every ~9s a droplet of a new colour falls in from the top, accelerating,
 * and on impact the pool of idle particles bursts outward — a few fast ones streak into wispy
 * tendrils, the rest bloom into a cloud. The burst velocity decays and a fine, fast-evolving
 * divergence-free churn (curl of high-frequency sines) plus diffusion jitter takes over, so the
 * cloud swirls and slowly disperses until its particles die and wait for the next drop.
 * Landing points come from a hash-scrambled per-drop seed so consecutive drops land in genuinely
 * different places. Clouds keep moving and dissolving — OLED-safe.
 */
class InkRenderer : GLSurfaceView.Renderer {
    private val n = 2000
    private val dropPeriod = 9.0f
    private val fallTime = 1.1f      // droplet fall duration; impact at k*dropPeriod + fallTime

    private val posX = FloatArray(n); private val posY = FloatArray(n)
    private val velX = FloatArray(n); private val velY = FloatArray(n)
    private val age = FloatArray(n)
    private val life = FloatArray(n)
    private val alive = BooleanArray(n)
    private val cr = FloatArray(n); private val cg = FloatArray(n); private val cb = FloatArray(n)
    private var lastBurst = -1       // drop index whose burst already happened

    private val palette = arrayOf(
        floatArrayOf(0.85f, 0.20f, 0.75f),   // magenta
        floatArrayOf(0.20f, 0.60f, 1.00f),   // azure
        floatArrayOf(0.30f, 0.90f, 0.80f),   // turquoise
        floatArrayOf(0.60f, 0.35f, 1.00f),   // violet
        floatArrayOf(0.95f, 0.50f, 0.25f),   // amber
    )

    private val pointBuf = GlUtil.directFloats((n + 2) * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var hPx = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        // pre-seed: some particles alive in clouds from "past" drops, the rest idle and ready
        // to burst with the first droplet
        for (i in 0 until n) {
            if (rnd() < 0.4f) {
                val di = -1 - (rnd() * 2f).toInt()
                val e = dropOf(di)
                life[i] = 8f + rnd() * 6f
                age[i] = rnd() * life[i] * 0.9f
                posX[i] = e[0] + gauss() * (0.015f + age[i] * 0.010f)
                posY[i] = e[1] + gauss() * (0.015f + age[i] * 0.010f)
                cr[i] = e[2]; cg[i] = e[3]; cb[i] = e[4]
                alive[i] = true
            } else {
                alive[i] = false
            }
        }
    }

    private fun rnd() = Math.random().toFloat()
    private fun gauss() = (rnd() + rnd() + rnd() + rnd() - 2f) * 0.87f

    /**
     * Deterministic landing point + colour for drop [idx]: (x, y, r, g, b). The seed is
     * hash-scrambled — sequential java.util.Random seeds give correlated first outputs, which
     * made every drop land in nearly the same spot.
     */
    private fun dropOf(idx: Int): FloatArray {
        val rs = java.util.Random((idx.toLong() + 7L) * -0x61c8864680b583ebL)
        rs.nextLong()   // extra warm-up draw
        val c = palette[rs.nextInt(palette.size)]
        return floatArrayOf(0.15f + 0.70f * rs.nextFloat(), 0.25f + 0.50f * rs.nextFloat(), c[0], c[1], c[2])
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
        val drop = dropOf(dropIdx)
        val impactT = dropIdx * dropPeriod + fallTime

        // burst: on impact, every idle particle spawns at the landing point
        if (ts >= impactT && lastBurst != dropIdx) {
            lastBurst = dropIdx
            for (i in 0 until n) {
                if (alive[i]) continue
                posX[i] = drop[0] + gauss() * 0.006f
                posY[i] = drop[1] + gauss() * 0.006f
                val ang = rnd() * 6.2831855f
                // most bloom gently; a few streak out fast as tendrils
                val spd = (0.05f + rnd() * 0.16f) * (if (rnd() < 0.15f) 2.2f else 1f)
                velX[i] = cos(ang) * spd
                velY[i] = sin(ang) * spd
                age[i] = 0f
                life[i] = 8f + rnd() * 6f
                cr[i] = drop[2]; cg[i] = drop[3]; cb[i] = drop[4]
                alive[i] = true
            }
        }

        val burstDecay = exp(-dtf / 1.2f)
        val jit = 0.0015f * kotlin.math.sqrt(dtf * 60f)

        pointBuf.clear()
        var count = 0
        for (i in 0 until n) {
            if (!alive[i]) continue
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
            if (age[i] > life[i]) { alive[i] = false; continue }

            val lf = age[i] / life[i]
            val env = min(age[i] / 0.3f, 1f) * min((1f - lf) / 0.4f, 1f)
            pointBuf.put(posX[i] * 2f - 1f).put(1f - posY[i] * 2f)
            pointBuf.put(cr[i]).put(cg[i]).put(cb[i]).put(0.06f * env)
            pointBuf.put((3.5f + 9f * lf) * hPx / 720f)
            count++
        }

        // the falling droplet itself (accelerating, with a small glow)
        if (ts >= impactT - fallTime && ts < impactT) {
            val u = 1f - (impactT - ts) / fallTime
            val dy = -0.03f + (drop[1] + 0.03f) * u * u
            pointBuf.put(drop[0] * 2f - 1f).put(1f - dy * 2f)
            pointBuf.put(drop[2]).put(drop[3]).put(drop[4]).put(0.9f)
            pointBuf.put(7f * hPx / 720f)
            count++
            pointBuf.put(drop[0] * 2f - 1f).put(1f - dy * 2f)
            pointBuf.put(1f).put(1f).put(1f).put(0.35f)
            pointBuf.put(3.5f * hPx / 720f)
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
