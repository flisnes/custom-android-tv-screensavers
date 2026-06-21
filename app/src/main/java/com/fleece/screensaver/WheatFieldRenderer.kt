package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * Golden wheat field under a warm sky. Each stalk is a thin tapered blade that bends in the wind.
 * The wind is a field whose phase depends on position, so gusts travel ACROSS the field as waves
 * (a stalk bends a beat after its neighbour) rather than everything swaying in unison; a per-stalk
 * random offset + light flutter keeps it from looking mechanical. The whole scene drifts slowly
 * for OLED safety.
 */
class WheatFieldRenderer : GLSurfaceView.Renderer {
    private val nStalks = 2200
    private val horizon = 0.42f

    // per-stalk, sorted far->near so the draw order gives correct overlap
    private val bx = FloatArray(nStalks)      // base x (0..1)
    private val by = FloatArray(nStalks)      // base y (0..1, horizon..1)
    private val hgt = FloatArray(nStalks)     // height (fraction of screen)
    private val hw = FloatArray(nStalks)      // half width at base
    private val swayAmp = FloatArray(nStalks) // max horizontal lean (fraction of width)
    private val phase = FloatArray(nStalks)   // per-stalk wind phase offset
    private val ampMul = FloatArray(nStalks)
    private val depth = FloatArray(nStalks)
    private val baseR = FloatArray(nStalks); private val baseG = FloatArray(nStalks); private val baseB = FloatArray(nStalks)
    private val tipR = FloatArray(nStalks); private val tipG = FloatArray(nStalks); private val tipB = FloatArray(nStalks)

    private val stalkBuf = GlUtil.directFloats(nStalks * 12 * 6)
    private val skyBuf = GlUtil.directFloats(4 * 6)
    private val floorBuf = GlUtil.directFloats(4 * 6)

    private var flatProg = 0
    private var fPos = 0; private var fColor = 0
    private var lastNanos = 0L
    private var t = 0.0

    init {
        // generate base positions, then sort far->near (ascending base y) for correct overlap
        val tmpBy = FloatArray(nStalks) { horizon + 0.02f + (1f - horizon - 0.02f) * pow(rnd(), 1.15f) }
        val ord = (0 until nStalks).sortedBy { tmpBy[it] }
        val hazeR = 0.78f; val hazeG = 0.70f; val hazeB = 0.55f
        for (k in 0 until nStalks) {
            val src = ord[k]
            val byv = tmpBy[src]
            by[k] = byv
            bx[k] = rnd()
            val d = (byv - horizon) / (1f - horizon)   // 0 far .. 1 near
            depth[k] = d
            hgt[k] = lerp(0.05f, 0.20f, d)
            hw[k] = lerp(0.0011f, 0.0030f, d)
            swayAmp[k] = lerp(0.006f, 0.020f, d)
            phase[k] = rnd() * 6.2831855f
            ampMul[k] = 0.8f + rnd() * 0.5f
            // colour: amber base, golden tip, with random variation; far stalks hazier + dimmer
            val v = 0.85f + rnd() * 0.30f
            val dim = lerp(0.55f, 1.0f, d)
            val haze = (1f - d) * 0.45f
            baseR[k] = mix(0.44f * v, hazeR, haze) * dim
            baseG[k] = mix(0.31f * v, hazeG, haze) * dim
            baseB[k] = mix(0.10f * v, hazeB, haze) * dim
            tipR[k] = mix(0.95f * v, hazeR, haze) * dim
            tipG[k] = mix(0.80f * v, hazeG, haze) * dim
            tipB[k] = mix(0.36f * v, hazeB, haze) * dim
        }
    }

    private fun rnd() = Math.random().toFloat()
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun pow(x: Float, p: Float) = Math.pow(x.toDouble(), p.toDouble()).toFloat()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.06f, 0.09f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        flatProg = GlUtil.link(GlUtil.FLAT_VS, GlUtil.FLAT_FS)
        fPos = GLES20.glGetAttribLocation(flatProg, "aPos")
        fColor = GLES20.glGetAttribLocation(flatProg, "aColor")
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    private fun wind(nx: Float, d: Float, ts: Float, ph: Float): Float {
        val p = nx * 6.0f + d * 1.5f + ph
        val base = 0.6f * sin(p - ts * 0.9f) + 0.4f * sin(p * 0.5f - ts * 0.6f + 1.7f)
        val gust = 0.5f + 0.5f * sin(nx * 2.2f - ts * 0.5f)   // large-scale gust front rolling across
        val flutter = 0.06f * sin(ts * 3.5f + ph * 5f)
        return base * (0.35f + 0.75f * gust) + flutter
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos == 0L) lastNanos = now
        var dt = (now - lastNanos) / 1_000_000_000.0
        lastNanos = now
        if (dt > 0.05) dt = 0.05
        t += dt
        val ts = t.toFloat()
        val dx = sin(ts * 0.02f) * 0.008f       // slow OLED drift
        val dy = sin(ts * 0.031f) * 0.006f

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(flatProg)

        // sky (top -> horizon) and field floor (horizon -> bottom) so gaps between stalks aren't black
        buildQuad(skyBuf, 0f + dy, horizon + dy, 0.40f, 0.52f, 0.72f, 0.97f, 0.84f, 0.60f)
        draw(skyBuf, 4)
        buildQuad(floorBuf, horizon + dy, 1.2f + dy, 0.82f, 0.66f, 0.38f, 0.30f, 0.21f, 0.07f)
        draw(floorBuf, 4)

        // stalks (far -> near order already; painter overlap)
        stalkBuf.clear()
        for (i in 0 until nStalks) {
            val s = swayAmp[i] * ampMul[i] * wind(bx[i], depth[i], ts, phase[i])
            val bxi = bx[i] + dx
            val byi = by[i] + dy
            val topY = byi - hgt[i]
            val midY = byi - hgt[i] * 0.5f
            val dxMid = s * 0.33f
            val dxTop = s
            val hwB = hw[i]; val hwM = hwB * 0.6f; val hwT = hwB * 0.28f
            val mr = (baseR[i] + tipR[i]) * 0.5f; val mg = (baseG[i] + tipG[i]) * 0.5f; val mb = (baseB[i] + tipB[i]) * 0.5f
            // base segment
            v(bxi - hwB, byi, baseR[i], baseG[i], baseB[i]); v(bxi + hwB, byi, baseR[i], baseG[i], baseB[i]); v(bxi + dxMid - hwM, midY, mr, mg, mb)
            v(bxi + dxMid - hwM, midY, mr, mg, mb); v(bxi + hwB, byi, baseR[i], baseG[i], baseB[i]); v(bxi + dxMid + hwM, midY, mr, mg, mb)
            // tip segment
            v(bxi + dxMid - hwM, midY, mr, mg, mb); v(bxi + dxMid + hwM, midY, mr, mg, mb); v(bxi + dxTop - hwT, topY, tipR[i], tipG[i], tipB[i])
            v(bxi + dxTop - hwT, topY, tipR[i], tipG[i], tipB[i]); v(bxi + dxMid + hwM, midY, mr, mg, mb); v(bxi + dxTop + hwT, topY, tipR[i], tipG[i], tipB[i])
        }
        stalkBuf.position(0)
        draw(stalkBuf, nStalks * 12)
    }

    private fun v(x: Float, y: Float, r: Float, g: Float, b: Float) {
        stalkBuf.put(x * 2f - 1f).put(1f - y * 2f).put(r).put(g).put(b).put(1f)
    }

    private fun buildQuad(buf: java.nio.FloatBuffer, yTop: Float, yBot: Float,
                          tr: Float, tg: Float, tb: Float, br: Float, bg: Float, bb: Float) {
        buf.clear()
        val nyT = 1f - yTop * 2f; val nyB = 1f - yBot * 2f
        buf.put(-1f).put(nyT).put(tr).put(tg).put(tb).put(1f)
        buf.put(1f).put(nyT).put(tr).put(tg).put(tb).put(1f)
        buf.put(-1f).put(nyB).put(br).put(bg).put(bb).put(1f)
        buf.put(1f).put(nyB).put(br).put(bg).put(bb).put(1f)
        buf.position(0)
    }

    private fun draw(buf: java.nio.FloatBuffer, verts: Int) {
        buf.position(0)
        GLES20.glEnableVertexAttribArray(fPos)
        GLES20.glVertexAttribPointer(fPos, 2, GLES20.GL_FLOAT, false, 24, buf)
        buf.position(2)
        GLES20.glEnableVertexAttribArray(fColor)
        GLES20.glVertexAttribPointer(fColor, 4, GLES20.GL_FLOAT, false, 24, buf)
        val mode = if (verts == 4) GLES20.GL_TRIANGLE_STRIP else GLES20.GL_TRIANGLES
        GLES20.glDrawArrays(mode, 0, verts)
    }
}
