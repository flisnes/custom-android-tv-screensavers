package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Starling murmuration: ~1700 boids in 3D chase a slowly wandering attractor while a coarse
 * spatial grid gives cheap alignment (steer toward local mean velocity) and separation (push away
 * from local centroid). A swirl term makes the flock orbit and fold instead of clumping. Comet
 * trails from the accumulation buffer read as motion blur. The attractor never rests, so the
 * flock sweeps the whole panel — OLED-safe.
 */
class MurmurationRenderer : GLSurfaceView.Renderer {
    private val n = 1700

    private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)
    private val vx = FloatArray(n); private val vy = FloatArray(n); private val vz = FloatArray(n)
    private val phase = FloatArray(n)
    private val cr = FloatArray(n); private val cg = FloatArray(n); private val cb = FloatArray(n)

    // spatial grid for alignment/separation, world box x[-2.5,3.1] y[-1.6,2.6] z[1.5,7.1]
    private val gx = 8; private val gy = 6; private val gz = 8
    private val cells = gx * gy * gz
    private val cCnt = FloatArray(cells)
    private val cVx = FloatArray(cells); private val cVy = FloatArray(cells); private val cVz = FloatArray(cells)
    private val cPx = FloatArray(cells); private val cPy = FloatArray(cells); private val cPz = FloatArray(cells)
    private val key = IntArray(n)

    private val pointBuf = GlUtil.directFloats(n * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var hPx = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        for (i in 0 until n) {
            px[i] = gauss() * 0.6f
            py[i] = gauss() * 0.35f
            pz[i] = 4.2f + gauss() * 0.5f
            vx[i] = gauss() * 0.3f; vy[i] = gauss() * 0.3f; vz[i] = gauss() * 0.3f
            phase[i] = rnd() * 6.2831855f
            cr[i] = clamp01(0.75f + (rnd() - 0.5f) * 0.12f)
            cg[i] = clamp01(0.82f + (rnd() - 0.5f) * 0.12f)
            cb[i] = clamp01(0.95f + (rnd() - 0.5f) * 0.12f)
        }
    }

    private fun rnd() = Math.random().toFloat()
    private fun gauss(): Float {   // sum of uniforms is plenty here
        return (rnd() + rnd() + rnd() + rnd() - 2f) * 0.87f
    }
    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)

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
        trail.resize(width, height, 0.05f)
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

        // wandering attractor
        val ax = 0.85f * sin(0.31f * ts) + 0.35f * sin(0.17f * ts + 1.3f)
        val ay = 0.40f * sin(0.23f * ts + 0.7f) + 0.20f * sin(0.41f * ts)
        val az = 4.2f + 0.9f * sin(0.19f * ts + 2.0f) + 0.4f * sin(0.37f * ts + 0.5f)
        // slowly precessing swirl axis
        var wx = sin(0.1f * ts); var wy = 0.85f; var wz = cos(0.13f * ts)
        val wl = sqrt(wx * wx + wy * wy + wz * wz)
        wx /= wl; wy /= wl; wz /= wl

        // grid pass 1: accumulate per-cell velocity and position
        java.util.Arrays.fill(cCnt, 0f)
        java.util.Arrays.fill(cVx, 0f); java.util.Arrays.fill(cVy, 0f); java.util.Arrays.fill(cVz, 0f)
        java.util.Arrays.fill(cPx, 0f); java.util.Arrays.fill(cPy, 0f); java.util.Arrays.fill(cPz, 0f)
        for (i in 0 until n) {
            val cxi = (((px[i] + 2.5f) / 0.7f).toInt()).coerceIn(0, gx - 1)
            val cyi = (((py[i] + 1.6f) / 0.7f).toInt()).coerceIn(0, gy - 1)
            val czi = (((pz[i] - 1.5f) / 0.7f).toInt()).coerceIn(0, gz - 1)
            val k = (cxi * gy + cyi) * gz + czi
            key[i] = k
            cCnt[k]++
            cVx[k] += vx[i]; cVy[k] += vy[i]; cVz[k] += vz[i]
            cPx[k] += px[i]; cPy[k] += py[i]; cPz[k] += pz[i]
        }

        pointBuf.clear()
        var count = 0
        for (i in 0 until n) {
            // attractor pull
            vx[i] += (ax - px[i]) * 1.0f * dtf
            vy[i] += (ay - py[i]) * 1.0f * dtf
            vz[i] += (az - pz[i]) * 1.0f * dtf
            // swirl = cross(axis, pos-attr)
            val rx = px[i] - ax; val ry = py[i] - ay; val rz = pz[i] - az
            vx[i] += (wy * rz - wz * ry) * 0.9f * dtf
            vy[i] += (wz * rx - wx * rz) * 0.9f * dtf
            vz[i] += (wx * ry - wy * rx) * 0.9f * dtf
            // grid alignment + separation
            val k = key[i]
            val c = if (cCnt[k] > 0f) cCnt[k] else 1f
            vx[i] += (cVx[k] / c - vx[i]) * 2.2f * dtf
            vy[i] += (cVy[k] / c - vy[i]) * 2.2f * dtf
            vz[i] += (cVz[k] / c - vz[i]) * 2.2f * dtf
            vx[i] += (px[i] - cPx[k] / c) * 2.6f * dtf
            vy[i] += (py[i] - cPy[k] / c) * 2.6f * dtf
            vz[i] += (pz[i] - cPz[k] / c) * 2.6f * dtf
            // per-bird wander
            vx[i] += 0.25f * sin(1.3f * ts + phase[i]) * dtf
            vy[i] += 0.20f * sin(1.7f * ts + phase[i] * 2f) * dtf
            vz[i] += 0.22f * sin(1.1f * ts + phase[i] * 3f) * dtf
            // speed clamp: birds never stop, never rocket
            val spd = sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]).coerceAtLeast(1e-6f)
            val s = spd.coerceIn(0.55f, 1.35f) / spd
            vx[i] *= s; vy[i] *= s; vz[i] *= s
            px[i] += vx[i] * dtf; py[i] += vy[i] * dtf; pz[i] += vz[i] * dtf

            val z = pz[i].coerceAtLeast(0.8f)
            val sx = 0.5f + px[i] / z * 0.62f
            val sy = 0.5f - py[i] / z * 1.05f
            pointBuf.put(sx * 2f - 1f).put(1f - sy * 2f)
            pointBuf.put(cr[i]).put(cg[i]).put(cb[i]).put(0.65f * min(1f, 2.5f / z))
            pointBuf.put((hPx * 0.019f / z).coerceAtLeast(1.2f))
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
