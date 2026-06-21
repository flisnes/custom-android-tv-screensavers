package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * "Waves under a starry sky" screensaver: a field of slowly-twinkling stars behind layered sea
 * waves. Waves are depth-graded — nearest (bottom) mid-blue, farthest (horizon) dark-blue — and
 * scroll with slight parallax. The whole scene drifts slowly for OLED safety.
 */
class WavesRenderer : GLSurfaceView.Renderer {
    // --- stars ---
    private val nStars = 340
    private val sx = FloatArray(nStars); private val sy = FloatArray(nStars)
    private val sBase = FloatArray(nStars); private val sPhase = FloatArray(nStars)
    private val sSpeed = FloatArray(nStars); private val sSize = FloatArray(nStars)
    private val sr = FloatArray(nStars); private val sg = FloatArray(nStars); private val sb = FloatArray(nStars)
    private val starBuf = GlUtil.directFloats(nStars * 7)

    // --- waves ---
    private val K = 6
    private val SEG = 170
    private val waveBuf = GlUtil.directFloats((SEG + 1) * 2 * 6)

    private var pointProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var flatProg = 0
    private var fPos = 0; private var fColor = 0
    private var w = 1f; private var h = 1f
    private var lastNanos = 0L
    private var t = 0.0

    init {
        for (i in 0 until nStars) {
            sx[i] = rnd(); sy[i] = rnd() * 0.58f        // sky region (waves cover the lower part)
            sBase[i] = 0.35f + rnd() * 0.65f
            sPhase[i] = rnd() * 6.2831855f
            sSpeed[i] = 0.3f + rnd() * 0.8f             // slow twinkle
            sSize[i] = 1.3f + rnd() * rnd() * 2.6f      // mostly small, a few larger
            sr[i] = 0.80f + rnd() * 0.20f
            sg[i] = 0.85f + rnd() * 0.15f
            sb[i] = 0.90f + rnd() * 0.10f
        }
    }

    private fun rnd() = Math.random().toFloat()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.01f, 0.02f, 0.05f, 1f)   // near-black with a faint night-blue
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        pointProg = GlUtil.link(GlUtil.POINT_VS, GlUtil.POINT_FS)
        pPos = GLES20.glGetAttribLocation(pointProg, "aPos")
        pColor = GLES20.glGetAttribLocation(pointProg, "aColor")
        pSize = GLES20.glGetAttribLocation(pointProg, "aSize")
        flatProg = GlUtil.link(GlUtil.FLAT_VS, GlUtil.FLAT_FS)
        fPos = GLES20.glGetAttribLocation(flatProg, "aPos")
        fColor = GLES20.glGetAttribLocation(flatProg, "aColor")
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        w = width.toFloat(); h = height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos == 0L) lastNanos = now
        var dt = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        if (dt > 0.05) dt = 0.05
        t += dt
        val ts = t.toFloat()

        // OLED-safe slow global drift
        val driftY = sin(ts * 0.04f) * 0.012f
        val driftX = sin(ts * 0.025f) * 0.010f

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)

        // Stars (additive, twinkling)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        starBuf.clear()
        for (i in 0 until nStars) {
            val a = sBase[i] * (0.55f + 0.45f * sin(ts * sSpeed[i] + sPhase[i]))
            val nx = (sx[i] + driftX) * 2f - 1f
            val ny = 1f - (sy[i] + driftY) * 2f
            starBuf.put(nx).put(ny).put(sr[i]).put(sg[i]).put(sb[i]).put(a * 0.9f).put(sSize[i])
        }
        starBuf.position(0)
        drawStars()

        // Waves (opaque, back-to-front so near layers occlude far ones + the lower stars)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (l in 0 until K) {
            val f = l / (K - 1f)
            val baseY = lerp(0.47f, 0.82f, f) + driftY
            val amp = lerp(0.013f, 0.040f, f)
            val baseFreq = lerp(7.0f, 2.6f, f)         // far = more, smaller ripples
            val speed = lerp(0.6f, 1.5f, f)            // near scrolls faster (parallax)
            val seed = l * 1.7f
            val group = 0.82f + 0.18f * sin(ts * 0.18f + seed)   // wave groups swell in/out
            val r = lerp(0.03f, 0.22f, f); val g = lerp(0.08f, 0.42f, f); val b = lerp(0.20f, 0.62f, f)
            buildWave(baseY, amp * group, baseFreq, ts, speed, seed, r, g, b)
            drawWave()
        }
    }

    /**
     * Builds one wave layer as a superposition of several travelling sine components with different
     * wavelengths, speeds, and directions. Because the components move at different rates (and some
     * travel the opposite way), the crest pattern continuously morphs via interference instead of
     * rigidly sliding. A vertical colour ramp (lighter crest, darker base) makes it read as water.
     */
    private fun buildWave(baseY: Float, amp: Float, baseFreq: Float, t: Float, speed: Float,
                          seed: Float, r: Float, g: Float, b: Float) {
        val tr = minOf(1f, r * 1.20f); val tg = minOf(1f, g * 1.20f); val tb = minOf(1f, b * 1.20f)
        val br = r * 0.72f; val bg = g * 0.72f; val bb = b * 0.72f
        val a = baseFreq * 6.2831855f
        val tt = t * speed
        waveBuf.clear()
        for (s in 0..SEG) {
            val xn = s / SEG.toFloat()
            var hh = sin(xn * a + tt + seed)
            hh += 0.50f * sin(xn * a * 2.3f - tt * 1.55f + seed * 1.7f)   // shorter, opposite way
            hh += 0.30f * sin(xn * a * 3.7f + tt * 0.70f + seed * 2.3f)   // chop
            hh += 0.62f * sin(xn * a * 0.55f - tt * 0.50f + seed * 0.9f)  // long swell, opposite way
            hh += 0.18f * sin(xn * a * 2.0f + tt * 1.20f + seed * 0.3f)   // crest detail
            val topY = baseY + amp * hh * 0.45f
            val ndcX = xn * 2f - 1f
            val ndcTop = 1f - topY * 2f
            waveBuf.put(ndcX).put(ndcTop).put(tr).put(tg).put(tb).put(1f)   // crest (lighter)
            waveBuf.put(ndcX).put(-1f).put(br).put(bg).put(bb).put(1f)      // base (darker)
        }
        waveBuf.position(0)
    }

    private fun drawStars() {
        GLES20.glUseProgram(pointProg)
        starBuf.position(0)
        GLES20.glEnableVertexAttribArray(pPos)
        GLES20.glVertexAttribPointer(pPos, 2, GLES20.GL_FLOAT, false, 28, starBuf)
        starBuf.position(2)
        GLES20.glEnableVertexAttribArray(pColor)
        GLES20.glVertexAttribPointer(pColor, 4, GLES20.GL_FLOAT, false, 28, starBuf)
        starBuf.position(6)
        GLES20.glEnableVertexAttribArray(pSize)
        GLES20.glVertexAttribPointer(pSize, 1, GLES20.GL_FLOAT, false, 28, starBuf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, nStars)
    }

    private fun drawWave() {
        GLES20.glUseProgram(flatProg)
        waveBuf.position(0)
        GLES20.glEnableVertexAttribArray(fPos)
        GLES20.glVertexAttribPointer(fPos, 2, GLES20.GL_FLOAT, false, 24, waveBuf)
        waveBuf.position(2)
        GLES20.glEnableVertexAttribArray(fColor)
        GLES20.glVertexAttribPointer(fColor, 4, GLES20.GL_FLOAT, false, 24, waveBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, (SEG + 1) * 2)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
}
