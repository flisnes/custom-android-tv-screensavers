package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 2.0 renderer for the black-hole simulation. Particles are additive point sprites;
 * the event horizon is an opaque disc that occludes the far side; the photon ring is an additive
 * annulus with a rotating bright hotspot. Far faster than the Canvas/WebView path and zero GC.
 */
class BlackHoleRenderer : GLSurfaceView.Renderer {

    private val sim = Sim(2800)
    private val n = sim.n

    // viewing / look constants (match the mockup)
    private val tilt = Math.toRadians(72.0)
    private val sinT = sin(tilt).toFloat()
    private val cosT = cos(tilt).toFloat()
    private val luma = 0.72f
    private val vref = sqrt(Sim.G / 0.25f)   // beaming reference speed

    private var w = 1f
    private var h = 1f
    private var s = 1f                        // world -> px
    private var lastNanos = 0L
    private var tMs = 0.0

    // colour LUT (0..1 rgb), indexed by normalized radius
    private val nc = 96
    private val lut = FloatArray(nc * 3)

    // GL programs
    private var pointProg = 0
    private var flatProg = 0
    private var pPos = 0; private var pColor = 0; private var pSize = 0
    private var fPos = 0; private var fColor = 0

    // vertex buffers
    private val pointBuf = directFloats(n * 7)                 // x,y, r,g,b,a, size
    private val FANS = 64
    private val glowBuf = directFloats((FANS + 2) * 6)
    private val holeBuf = directFloats((FANS + 2) * 6)
    private val RSEG = 80
    private val ringBuf = directFloats((RSEG + 1) * 2 * 6)

    // trail accumulation: render into an FBO, fade it each frame, then blit to screen
    private var fbo = 0
    private var fboTex = 0
    private var texProg = 0
    private var tPos = 0; private var tTex = 0; private var uTex = 0
    private val fadeAlpha = 0.18f
    private val fadeQuad = directFloats(4 * 6)   // fullscreen NDC quad, pos+color (flat prog)
    private val fullQuad = directFloats(4 * 4)   // fullscreen NDC quad, pos+uv (tex prog)

    init {
        // build colour ramp LUT (blue-white -> white -> gold -> orange -> ember)
        val stops = arrayOf(
            0.00f to floatArrayOf(205f, 226f, 255f),
            0.16f to floatArrayOf(255f, 251f, 240f),
            0.42f to floatArrayOf(255f, 201f, 120f),
            0.70f to floatArrayOf(255f, 126f, 55f),
            1.00f to floatArrayOf(214f, 66f, 28f),
        )
        for (i in 0 until nc) {
            val t = i / (nc - 1f)
            var c = stops.last().second
            for (k in 1 until stops.size) {
                if (t <= stops[k].first) {
                    val a = stops[k - 1]; val b = stops[k]
                    val f = (t - a.first) / (b.first - a.first)
                    c = floatArrayOf(
                        a.second[0] + (b.second[0] - a.second[0]) * f,
                        a.second[1] + (b.second[1] - a.second[1]) * f,
                        a.second[2] + (b.second[2] - a.second[2]) * f,
                    )
                    break
                }
            }
            lut[i * 3] = c[0] / 255f; lut[i * 3 + 1] = c[1] / 255f; lut[i * 3 + 2] = c[2] / 255f
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        pointProg = link(POINT_VS, POINT_FS)
        flatProg = link(FLAT_VS, FLAT_FS)
        pPos = GLES20.glGetAttribLocation(pointProg, "aPos")
        pColor = GLES20.glGetAttribLocation(pointProg, "aColor")
        pSize = GLES20.glGetAttribLocation(pointProg, "aSize")
        fPos = GLES20.glGetAttribLocation(flatProg, "aPos")
        fColor = GLES20.glGetAttribLocation(flatProg, "aColor")

        texProg = link(TEX_VS, TEX_FS)
        tPos = GLES20.glGetAttribLocation(texProg, "aPos")
        tTex = GLES20.glGetAttribLocation(texProg, "aTex")
        uTex = GLES20.glGetUniformLocation(texProg, "uTex")

        // static fullscreen quads (triangle strip). fadeQuad = black @ fadeAlpha; fullQuad = pos+uv.
        fadeQuad.clear()
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        for (k in 0 until 4) {
            fadeQuad.put(corners[k * 2]).put(corners[k * 2 + 1])
            fadeQuad.put(0f).put(0f).put(0f).put(fadeAlpha)
        }
        fadeQuad.position(0)
        fullQuad.clear()
        floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
            .forEach { fullQuad.put(it) }
        fullQuad.position(0)

        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        w = width.toFloat(); h = height.toFloat()
        s = min(w, h) * 0.8f
        createFbo(width, height)
    }

    private fun createFbo(width: Int, height: Int) {
        if (fboTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); fboTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fb = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); fbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNanos == 0L) lastNanos = now
        var dtFrames = (now - lastNanos) / 16_666_667.0
        lastNanos = now
        if (dtFrames > 2.2) dtFrames = 2.2
        tMs += dtFrames * 16.6667
        val t = tMs

        // advance simulation (2 sub-steps for stability near the hole)
        val h2 = (dtFrames * 0.5).toFloat()
        sim.step(h2); sim.step(h2)

        // OLED-safe wander of the whole scene
        val driftX = (sin(t * 0.00005) * 0.11 + sin(t * 0.000019 + 0.7) * 0.05) * w
        val driftY = (cos(t * 0.000061 + 1.3) * 0.07 + sin(t * 0.000023 + 2.1) * 0.04) * h
        val ox = w / 2f + driftX.toFloat()
        val oy = h / 2f + driftY.toFloat()

        buildPoints(ox, oy)
        buildGlow(ox, oy)
        buildHole(ox, oy)
        buildRing(ox, oy, t)

        // --- render the scene into the trail-accumulation FBO (no clear: keep history) ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glEnable(GLES20.GL_BLEND)

        // fade the previous frame toward black (motion trails)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawFlat(fadeQuad, 4, GLES20.GL_TRIANGLE_STRIP)

        // back particles (additive)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        drawPoints(0, backCount)
        // warm back-glow (additive)
        drawFlat(glowBuf, FANS + 2, GLES20.GL_TRIANGLE_FAN)
        // event horizon: opaque, occludes the far side
        GLES20.glDisable(GLES20.GL_BLEND)
        drawFlat(holeBuf, FANS + 2, GLES20.GL_TRIANGLE_FAN)
        // photon ring (additive)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        drawFlat(ringBuf, (RSEG + 1) * 2, GLES20.GL_TRIANGLE_STRIP)
        // front particles (additive)
        drawPoints(backCount, frontCount)

        // --- blit the accumulation texture to the screen ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(texProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(uTex, 0)
        fullQuad.position(0)
        GLES20.glEnableVertexAttribArray(tPos)
        GLES20.glVertexAttribPointer(tPos, 2, GLES20.GL_FLOAT, false, 16, fullQuad)
        fullQuad.position(2)
        GLES20.glEnableVertexAttribArray(tTex)
        GLES20.glVertexAttribPointer(tTex, 2, GLES20.GL_FLOAT, false, 16, fullQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---- per-frame geometry ----
    private var backCount = 0
    private var frontCount = 0

    private fun buildPoints(ox: Float, oy: Float) {
        pointBuf.clear()
        // back half first (zr < 0), then front half (zr >= 0)
        backCount = fillPoints(ox, oy, back = true)
        frontCount = fillPoints(ox, oy, back = false)
        pointBuf.position(0)
    }

    private fun fillPoints(ox: Float, oy: Float, back: Boolean): Int {
        var count = 0
        for (i in 0 until n) {
            val px = sim.x[i]; val py = sim.y[i]; val pz = sim.z[i]
            val yr = py * cosT - pz * sinT
            val zr = py * sinT + pz * cosT
            if (back) { if (zr >= 0f) continue } else { if (zr < 0f) continue }

            val sxPx = ox + px * s
            val syPx = oy + yr * s
            if (sxPx < -8 || sxPx > w + 8 || syPx < -8 || syPx > h + 8) continue

            val r = sqrt(px * px + py * py + pz * pz)
            var tt = (r - Sim.Rh) / (Sim.R_out - Sim.Rh); if (tt < 0f) tt = 0f else if (tt > 1f) tt = 1f
            val pf = exp(-(pz * pz) / 0.0045f)
            var a = (0.85f - 0.6f * tt) * (0.25f + 0.75f * pf)
            a *= max(0.12f, min(1f, 1.25f - r / Sim.R_spawn))
            val vzr = sim.vy[i] * sinT + sim.vz[i] * cosT
            a *= 1f + 0.7f * max(-1f, min(1f, vzr / vref))
            a *= luma
            if (a <= 0.012f) continue
            if (a > 0.5f) a = 0.5f

            val ci = (tt * (nc - 1)).toInt().coerceIn(0, nc - 1)
            val size = (0.7f + 1.1f * pf * (1f - tt * 0.6f)) * SIZE_SCALE
            pointBuf.put(ndcX(sxPx)).put(ndcY(syPx))
            pointBuf.put(lut[ci * 3]).put(lut[ci * 3 + 1]).put(lut[ci * 3 + 2]).put(a)
            pointBuf.put(size)
            count++
        }
        return count
    }

    private fun buildGlow(ox: Float, oy: Float) {
        val rad = Sim.Rh * s * 3.2f
        glowBuf.clear()
        // centre (warm, bright) then rim (alpha 0)
        putV(glowBuf, ox, oy, 1f, 0.667f, 0.353f, 0.10f * luma)
        for (i in 0..FANS) {
            val ang = i / FANS.toFloat() * 6.2831855f
            putV(glowBuf, ox + cos(ang) * rad, oy + sin(ang) * rad, 1f, 0.667f, 0.353f, 0f)
        }
        glowBuf.position(0)
    }

    private fun buildHole(ox: Float, oy: Float) {
        val rad = Sim.Rh * s
        holeBuf.clear()
        putV(holeBuf, ox, oy, 0f, 0f, 0f, 1f)
        for (i in 0..FANS) {
            val ang = i / FANS.toFloat() * 6.2831855f
            putV(holeBuf, ox + cos(ang) * rad, oy + sin(ang) * rad, 0f, 0f, 0f, 1f)
        }
        holeBuf.position(0)
    }

    private fun buildRing(ox: Float, oy: Float, t: Double) {
        val rIn = Sim.Rh * s * 1.02f
        val rOut = Sim.Rh * s * 1.2f
        val phase = (t * 0.00035).toFloat()
        ringBuf.clear()
        for (i in 0..RSEG) {
            val ang = i / RSEG.toFloat() * 6.2831855f
            val lobe = run { val c = 0.55f + 0.45f * cos(ang - phase); c * c }
            val a = luma * (0.06f + 0.42f * lobe)
            val ca = cos(ang); val sa = sin(ang)
            putV(ringBuf, ox + ca * rIn, oy + sa * rIn, 1f, 0.91f, 0.78f, a)
            putV(ringBuf, ox + ca * rOut, oy + sa * rOut, 1f, 0.91f, 0.78f, a * 0.5f)
        }
        ringBuf.position(0)
    }

    // ---- draw helpers ----
    private fun drawPoints(first: Int, count: Int) {
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
        GLES20.glDrawArrays(GLES20.GL_POINTS, first, count)
    }

    private fun drawFlat(buf: FloatBuffer, count: Int, mode: Int) {
        GLES20.glUseProgram(flatProg)
        buf.position(0)
        GLES20.glEnableVertexAttribArray(fPos)
        GLES20.glVertexAttribPointer(fPos, 2, GLES20.GL_FLOAT, false, 24, buf)
        buf.position(2)
        GLES20.glEnableVertexAttribArray(fColor)
        GLES20.glVertexAttribPointer(fColor, 4, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glDrawArrays(mode, 0, count)
    }

    private fun putV(b: FloatBuffer, xPx: Float, yPx: Float, r: Float, g: Float, bl: Float, a: Float) {
        b.put(ndcX(xPx)).put(ndcY(yPx)).put(r).put(g).put(bl).put(a)
    }

    private fun ndcX(px: Float) = px / w * 2f - 1f
    private fun ndcY(px: Float) = 1f - px / h * 2f

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        return p
    }
    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src); GLES20.glCompileShader(sh)
        return sh
    }

    companion object {
        private const val SIZE_SCALE = 2.6f
        private fun directFloats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private const val POINT_VS = """
            attribute vec2 aPos; attribute vec4 aColor; attribute float aSize;
            varying vec4 vColor;
            void main(){ gl_Position = vec4(aPos, 0.0, 1.0); gl_PointSize = aSize; vColor = aColor; }
        """
        private const val POINT_FS = """
            precision mediump float; varying vec4 vColor;
            void main(){
              vec2 d = gl_PointCoord - vec2(0.5);
              float a = smoothstep(0.5, 0.0, length(d));
              gl_FragColor = vec4(vColor.rgb, vColor.a * a);
            }
        """
        private const val FLAT_VS = """
            attribute vec2 aPos; attribute vec4 aColor;
            varying vec4 vColor;
            void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vColor = aColor; }
        """
        private const val FLAT_FS = """
            precision mediump float; varying vec4 vColor;
            void main(){ gl_FragColor = vColor; }
        """
        private const val TEX_VS = """
            attribute vec2 aPos; attribute vec2 aTex;
            varying vec2 vTex;
            void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }
        """
        private const val TEX_FS = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main(){ gl_FragColor = texture2D(uTex, vTex); }
        """
    }
}
