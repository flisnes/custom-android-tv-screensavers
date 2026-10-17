package com.fleece.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * Map lettering for the contour scenes (crater names, the Earth close-up's place name): Jost Medium from
 * assets/fonts (SIL OFL; a Futura revival, the typeface of the Apollo 11 plaque), white with a black outline
 * around the letters themselves, so a name stays readable where it crosses contour lines.
 * A [Pen] measures and draws one style; [atlas] stacks a set of names into one bitmap (load thread) and
 * [Layer] draws quads from it (GL thread).
 */
object MapText {

    /** One style of lettering. [tracking] is extra letter spacing in ems. */
    class Pen(context: Context, sizePx: Float, italic: Boolean = false, private val caps: Boolean = false, tracking: Float = 0.04f) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(context, italic); textSize = sizePx; letterSpacing = tracking; color = Color.WHITE
        }
        private val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE; strokeWidth = sizePx * OUTLINE; strokeJoin = Paint.Join.ROUND; color = Color.BLACK
        }
        private val pad = ceil(sizePx * (OUTLINE / 2f + 0.06f))           // room for the outline and anti-aliasing
        val height = ceil(fill.descent() - fill.ascent() + 2 * pad)

        fun text(s: String) = if (caps) s.uppercase() else s
        fun width(s: String) = ceil(fill.measureText(text(s)) + 2 * pad)
        /** Outline first, then the white letters over it. */
        fun draw(canvas: Canvas, s: String, left: Float, top: Float) {
            val t = text(s); val base = top + pad - fill.ascent()
            canvas.drawText(t, left + pad, base, stroke)
            canvas.drawText(t, left + pad, base, fill)
        }
    }

    /** Where one name sits in an atlas: its size in px and its texture coordinates (v0 = top row). */
    class Glyphs(val w: Float, val h: Float, val v0: Float, val v1: Float, val u1: Float)

    /** Renders each name on its own row of one bitmap. */
    fun atlas(names: List<Pair<String, Pen>>): Pair<Bitmap, List<Glyphs>> {
        val ws = names.map { (s, pen) -> pen.width(s) }
        val aw = ws.max().toInt()
        val ah = names.sumOf { it.second.height.toInt() }
        val bmp = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        var y = 0
        val glyphs = names.mapIndexed { i, (s, pen) ->
            val h = pen.height.toInt()
            pen.draw(canvas, s, 0f, y.toFloat())
            Glyphs(ws[i], h.toFloat(), y / ah.toFloat(), (y + h) / ah.toFloat(), ws[i] / aw).also { y += h }
        }
        return bmp to glyphs
    }

    private const val OUTLINE = 0.22f          // outline stroke width in ems (half of it lies outside the letters)
    private val faces = HashMap<Boolean, Typeface>()

    @Synchronized private fun typeface(context: Context, italic: Boolean): Typeface = faces.getOrPut(italic) {
        try { Typeface.createFromAsset(context.assets, if (italic) "fonts/Jost-MediumItalic.ttf" else "fonts/Jost-Medium.ttf") }
        catch (e: Exception) {
            Log.e("MapText", "font missing", e)
            Typeface.create("sans-serif-medium", if (italic) Typeface.ITALIC else Typeface.NORMAL)
        }
    }

    /** GL side: one atlas texture and a batch of screen-space quads, each with its own opacity. */
    class Layer(maxQuads: Int) {
        private var prog = 0
        private var tex = 0
        private var uBright = -1
        private var hasAtlas = false
        private var count = 0
        private val verts: FloatBuffer = GlUtil.directFloats(maxQuads * 6 * 5)

        /** Call from onSurfaceCreated. */
        fun initGl(link: (String, String) -> Int) {
            prog = link(VS, FS)
            uBright = GLES30.glGetUniformLocation(prog, "uBright")
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tex = t[0]
            hasAtlas = false
        }

        /** Make [bmp] the atlas (recycles it). */
        fun upload(bmp: Bitmap) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            hasAtlas = true
        }

        fun begin() { verts.clear(); count = 0 }

        /** A name centred on screen px ([cx], [cy]) (y up), at [alpha] opacity. Snapped to whole px to stay crisp. */
        fun quad(g: Glyphs, cx: Float, cy: Float, alpha: Float, vw: Int, vh: Int) {
            if (alpha <= 0f || verts.remaining() < 30) return
            val l = Math.round(cx - g.w / 2).toFloat(); val b = Math.round(cy - g.h / 2).toFloat()
            val x0 = l / vw * 2f - 1f; val x1 = (l + g.w) / vw * 2f - 1f
            val y0 = b / vh * 2f - 1f; val y1 = (b + g.h) / vh * 2f - 1f
            // Bitmap row 0 is the top of the name; screen y grows upward.
            verts.put(floatArrayOf(
                x0, y0, 0f, g.v1, alpha, x1, y0, g.u1, g.v1, alpha, x1, y1, g.u1, g.v0, alpha,
                x0, y0, 0f, g.v1, alpha, x1, y1, g.u1, g.v0, alpha, x0, y1, 0f, g.v0, alpha))
            count++
        }

        /** Draws the quads since [begin]. [bright] scales the white letters only; the outline stays black. */
        fun draw(bright: Float) {
            if (count == 0 || !hasAtlas) return
            verts.flip()
            GLES30.glUseProgram(prog)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1f(uBright, bright)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)   // the atlas is premultiplied
            verts.position(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 20, verts)
            verts.position(2)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, verts)
            verts.position(4)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 20, verts)
            for (i in 0..2) GLES30.glEnableVertexAttribArray(i)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count * 6)
            for (i in 0..2) GLES30.glDisableVertexAttribArray(i)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aAlpha;
out vec2 vUv;
out float vAlpha;
void main() { vUv = aUv; vAlpha = aAlpha; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    private const val FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uBright;
in vec2 vUv;
in float vAlpha;
out vec4 fragColor;
void main() { vec4 t = texture(uTex, vUv); fragColor = vec4(t.rgb * uBright, t.a) * vAlpha; }
"""
}
