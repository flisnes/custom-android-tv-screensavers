package com.fleece.screensaver

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Shared GL ES 2.0 helpers + shader sources used by the screensaver scenes. */
object GlUtil {
    fun directFloats(count: Int): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    // Additive point sprite: aPos (NDC), aColor (rgba), aSize (px). Soft round falloff.
    const val POINT_VS = """
        attribute vec2 aPos; attribute vec4 aColor; attribute float aSize;
        varying vec4 vColor;
        void main(){ gl_Position = vec4(aPos, 0.0, 1.0); gl_PointSize = aSize; vColor = aColor; }
    """
    const val POINT_FS = """
        precision mediump float; varying vec4 vColor;
        void main(){
          float a = smoothstep(0.5, 0.0, length(gl_PointCoord - vec2(0.5)));
          gl_FragColor = vec4(vColor.rgb, vColor.a * a);
        }
    """

    // Flat solid colour: aPos (NDC), aColor (rgba).
    const val FLAT_VS = """
        attribute vec2 aPos; attribute vec4 aColor;
        varying vec4 vColor;
        void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vColor = aColor; }
    """
    const val FLAT_FS = """
        precision mediump float; varying vec4 vColor;
        void main(){ gl_FragColor = vColor; }
    """

    // Fullscreen textured blit: aPos (NDC), aTex (uv).
    const val TEX_VS = """
        attribute vec2 aPos; attribute vec2 aTex;
        varying vec2 vTex;
        void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }
    """
    const val TEX_FS = """
        precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
        void main(){ gl_FragColor = texture2D(uTex, vTex); }
    """
}

/**
 * Motion-trail accumulation buffer: render into an offscreen texture, fade it a little each frame,
 * then blit to screen. Gives particles comet trails with zero per-frame allocation.
 */
class TrailFbo {
    private var fbo = 0
    private var tex = 0
    private var texProg = 0
    private var flatProg = 0
    private var tPos = 0; private var tTex = 0; private var uTex = 0
    private var fPos = 0; private var fColor = 0
    private val fadeQuad = GlUtil.directFloats(4 * 6)
    private val subQuad = GlUtil.directFloats(4 * 6)
    private val fullQuad = GlUtil.directFloats(4 * 4)
    private var w = 0; private var h = 0

    companion object {
        // Extra per-frame constant subtraction. The multiplicative fade alone never reaches
        // black in an 8-bit buffer (value*0.97 rounds back to itself once dim), leaving grey
        // ghost trails; subtracting ~1.2/255 each frame guarantees pixels bottom out at 0.
        private const val SUB = 1.2f / 255f
    }

    fun init() {
        texProg = GlUtil.link(GlUtil.TEX_VS, GlUtil.TEX_FS)
        tPos = GLES20.glGetAttribLocation(texProg, "aPos")
        tTex = GLES20.glGetAttribLocation(texProg, "aTex")
        uTex = GLES20.glGetUniformLocation(texProg, "uTex")
        flatProg = GlUtil.link(GlUtil.FLAT_VS, GlUtil.FLAT_FS)
        fPos = GLES20.glGetAttribLocation(flatProg, "aPos")
        fColor = GLES20.glGetAttribLocation(flatProg, "aColor")
        fullQuad.clear()
        floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
            .forEach { fullQuad.put(it) }
        fullQuad.position(0)
    }

    fun resize(width: Int, height: Int, fadeAlpha: Float) {
        w = width; h = height
        if (tex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex, 0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fade = -1f
        setFade(fadeAlpha)
        val c = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        subQuad.clear()
        for (k in 0 until 4) { subQuad.put(c[k * 2]).put(c[k * 2 + 1]).put(SUB).put(SUB).put(SUB).put(1f) }
        subQuad.position(0)
    }

    private var fade = -1f

    /** Per-frame fade toward black (lower = longer trails); cheap to call every frame. */
    fun setFade(fadeAlpha: Float) {
        if (fadeAlpha == fade) return
        fade = fadeAlpha
        fadeQuad.clear()
        val c = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        for (k in 0 until 4) { fadeQuad.put(c[k * 2]).put(c[k * 2 + 1]).put(0f).put(0f).put(0f).put(fadeAlpha) }
        fadeQuad.position(0)
    }

    /** Bind the accumulation buffer and fade the previous frame toward black (call at frame start). */
    fun beginFrame() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(flatProg)
        fadeQuad.position(0)
        GLES20.glEnableVertexAttribArray(fPos)
        GLES20.glVertexAttribPointer(fPos, 2, GLES20.GL_FLOAT, false, 24, fadeQuad)
        fadeQuad.position(2)
        GLES20.glEnableVertexAttribArray(fColor)
        GLES20.glVertexAttribPointer(fColor, 4, GLES20.GL_FLOAT, false, 24, fadeQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // constant subtraction so dim trails decay all the way to pitch black (see SUB)
        GLES20.glBlendEquation(GLES20.GL_FUNC_REVERSE_SUBTRACT)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        subQuad.position(0)
        GLES20.glVertexAttribPointer(fPos, 2, GLES20.GL_FLOAT, false, 24, subQuad)
        subQuad.position(2)
        GLES20.glVertexAttribPointer(fColor, 4, GLES20.GL_FLOAT, false, 24, subQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
    }

    /** Unbind and blit the accumulation texture to the screen (call at frame end). */
    fun blit() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(texProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        fullQuad.position(0)
        GLES20.glEnableVertexAttribArray(tPos)
        GLES20.glVertexAttribPointer(tPos, 2, GLES20.GL_FLOAT, false, 16, fullQuad)
        fullQuad.position(2)
        GLES20.glEnableVertexAttribArray(tTex)
        GLES20.glVertexAttribPointer(tTex, 2, GLES20.GL_FLOAT, false, 16, fullQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
