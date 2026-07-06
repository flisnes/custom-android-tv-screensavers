package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whirlpool ocean: particles ride a field made of five drifting vortices whose strengths slowly
 * oscillate through zero (each eddy dissolves and re-forms spinning the other way). Long additive
 * trails wind into glowing spirals; brightness follows speed so the eddy cores and shear lines
 * light up. Everything drifts constantly — OLED-safe.
 */
class VortexRenderer : GLSurfaceView.Renderer {
    private val n = 2600
    private val nv = 5

    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val age = IntArray(n)
    private val life = IntArray(n)
    private val col = IntArray(n)

    private val voff = FloatArray(nv)
    private val vph = Array(nv) { FloatArray(4) }
    private val vcx = FloatArray(nv); private val vcy = FloatArray(nv); private val vs = FloatArray(nv)

    private val palette = arrayOf(
        floatArrayOf(0.15f, 0.75f, 0.85f),
        floatArrayOf(0.20f, 0.55f, 1.00f),
        floatArrayOf(0.30f, 0.95f, 0.80f),
        floatArrayOf(0.10f, 0.40f, 0.90f),
        floatArrayOf(0.45f, 0.85f, 1.00f),
    )

    private val pointBuf = GlUtil.directFloats(n * 7)
    private val trail = TrailFbo()
    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var hPx = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        for (k in 0 until nv) {
            voff[k] = rnd() * 6.2831855f
            for (j in 0 until 4) vph[k][j] = rnd() * 6.2831855f
        }
        for (i in 0 until n) {
            px[i] = rnd(); py[i] = rnd()
            life[i] = 260 + (rnd() * 300).toInt()
            age[i] = (rnd() * life[i]).toInt()
            col[i] = (rnd() * palette.size).toInt().coerceAtMost(palette.size - 1)
        }
    }

    private fun rnd() = Math.random().toFloat()

    private fun respawn(i: Int) {
        px[i] = rnd(); py[i] = rnd()
        age[i] = 0
        life[i] = 260 + (rnd() * 300).toInt()
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

        // vortex centres drift on slow Lissajous paths; strength oscillates through zero
        for (k in 0 until nv) {
            vcx[k] = 0.5f + 0.33f * sin(0.031f * ts + vph[k][0]) + 0.08f * sin(0.11f * ts + vph[k][1])
            vcy[k] = 0.5f + 0.30f * sin(0.023f * ts + vph[k][2]) + 0.07f * sin(0.09f * ts + vph[k][3])
            vs[k] = 0.05f * sin(0.06f * ts + voff[k])
        }
        val bgx = 0.010f * sin(0.05f * ts)
        val bgy = 0.008f * sin(0.041f * ts + 1.0f)

        pointBuf.clear()
        var count = 0
        for (i in 0 until n) {
            var vx = bgx; var vy = bgy
            for (k in 0 until nv) {
                val dx = px[i] - vcx[k]; val dy = py[i] - vcy[k]
                val r2 = dx * dx + dy * dy
                // rational falloff approximating exp(-r2/0.35)
                val f = 1f / (1f + 2.86f * r2)
                val w = vs[k] / (r2 + 0.015f) * f * f
                vx += -dy * w
                vy += dx * w
            }
            var spd = sqrt(vx * vx + vy * vy)
            if (spd > 0.35f) { val c = 0.35f / spd; vx *= c; vy *= c; spd = 0.35f }
            px[i] += vx * dtf
            py[i] += vy * dtf
            age[i]++
            if (age[i] > life[i] || px[i] < -0.05f || px[i] > 1.05f || py[i] < -0.05f || py[i] > 1.05f) {
                respawn(i)
            }
            val lf = age[i].toFloat() / life[i]
            val env = if (lf < 0.1f) lf / 0.1f else if (lf > 0.85f) (1f - lf) / 0.15f else 1f
            val c = palette[col[i]]
            pointBuf.put(px[i] * 2f - 1f).put(1f - py[i] * 2f)
            pointBuf.put(c[0]).put(c[1]).put(c[2])
            pointBuf.put((0.10f + 0.55f * min(spd / 0.20f, 1f)) * env)   // speed lights it up
            pointBuf.put(hPx * 3.0f / 720f)
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
