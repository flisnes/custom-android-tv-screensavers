package com.fleece.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Map lettering for the contour scenes (crater names, the Earth close-up's map names): Jost Medium from
 * assets/fonts (SIL OFL; a Futura revival, the typeface of the Apollo 11 plaque), white with a black outline
 * around the letters themselves, so a name stays readable where it crosses contour lines.
 * A [Pen] measures and draws one style; [atlas] stacks a set of names into one bitmap (load thread) and
 * [Layer] draws quads from it (GL thread), each with its own tint, opacity and angle.
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
        /** Room taken by a [Marker] before the name, including its gap. */
        private val markerW = sizePx * 0.95f

        fun text(s: String) = if (caps) s.uppercase() else s
        fun width(s: String, marker: Marker = Marker.NONE) =
            ceil(fill.measureText(text(s)) + 2 * pad + if (marker != Marker.NONE) markerW else 0f)
        /** x of the marker's centre from the left edge of the name's box. */
        fun markerX() = pad + fill.textSize * 0.26f
        /** Outline first, then the white letters over it; an outlined triangle in front if [marker] is set. */
        fun draw(canvas: Canvas, s: String, left: Float, top: Float, marker: Marker = Marker.NONE) {
            val t = text(s); val base = top + pad - fill.ascent()
            var x = left + pad
            if (marker != Marker.NONE) {
                val r = fill.textSize * 0.24f; val cx = left + markerX(); val cy = top + height / 2
                val tip = if (marker == Marker.PEAK) -1f else 1f            // bitmap y grows downward
                val tri = Path().apply {
                    moveTo(cx, cy + tip * r); lineTo(cx - r * 1.1f, cy - tip * r * 0.8f); lineTo(cx + r * 1.1f, cy - tip * r * 0.8f); close()
                }
                canvas.drawPath(tri, stroke); canvas.drawPath(tri, fill)
                x += markerW
            }
            canvas.drawText(t, x, base, stroke)
            canvas.drawText(t, x, base, fill)
        }
    }

    /** A small triangle in front of a name, sitting on the named point: up for a peak, down for a depression. */
    enum class Marker { NONE, PEAK, LOW }

    /**
     * Where one name sits in an atlas: its size in px, its texture coordinates (v0 = top row) and its anchor
     * ([ax], [ay] px from the left / top), the point that is placed on the map: the centre, or the marker.
     */
    class Glyphs(val w: Float, val h: Float, val v0: Float, val v1: Float, val u1: Float, val ax: Float = w / 2, val ay: Float = h / 2)

    /** One name to render. */
    class Entry(val text: String, val pen: Pen, val marker: Marker = Marker.NONE)

    /** Renders each name on its own row of one bitmap. */
    @JvmName("atlasOfPairs")
    fun atlas(names: List<Pair<String, Pen>>): Pair<Bitmap, List<Glyphs>> = atlas(names.map { Entry(it.first, it.second) })

    fun atlas(names: List<Entry>): Pair<Bitmap, List<Glyphs>> {
        val ws = names.map { it.pen.width(it.text, it.marker) }
        val aw = ws.max().toInt()
        val ah = names.sumOf { it.pen.height.toInt() }
        val bmp = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        var y = 0
        val glyphs = names.mapIndexed { i, e ->
            val h = e.pen.height.toInt()
            e.pen.draw(canvas, e.text, 0f, y.toFloat(), e.marker)
            val ax = if (e.marker != Marker.NONE) e.pen.markerX() else ws[i] / 2
            Glyphs(ws[i], h.toFloat(), y / ah.toFloat(), (y + h) / ah.toFloat(), ws[i] / aw, ax, h / 2f).also { y += h }
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

    /** GL side: one atlas texture and a batch of screen-space quads, each with its own tint, opacity and angle. */
    class Layer(maxQuads: Int) {
        private var prog = 0
        private var tex = 0
        private var uBright = -1
        private var hasAtlas = false
        private var count = 0
        private val verts: FloatBuffer = GlUtil.directFloats(maxQuads * 6 * STRIDE)

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

        /**
         * A name with its anchor at screen px ([cx], [cy]) (y up), at [alpha] opacity, tinted [rgb] (0xRRGGBB) and turned
         * [angle] radians counter-clockwise about the anchor. Level names are snapped to whole px to stay crisp.
         */
        fun quad(g: Glyphs, cx: Float, cy: Float, alpha: Float, vw: Int, vh: Int, rgb: Int = 0xFFFFFF, angle: Float = 0f) {
            if (alpha <= 0f || verts.remaining() < 6 * STRIDE) return
            val r = (rgb shr 16 and 255) / 255f; val gr = (rgb shr 8 and 255) / 255f; val bl = (rgb and 255) / 255f
            // Corners relative to the anchor, y up (bitmap row 0 is the top of the name).
            val l = -g.ax; val rt = g.w - g.ax; val b = g.ay - g.h; val t = g.ay
            val c = cos(angle); val s = sin(angle)
            var ox = cx; var oy = cy
            if (angle == 0f) { ox = Math.round(cx + l) - l; oy = Math.round(cy + b) - b }
            fun corner(x: Float, y: Float, u: Float, v: Float) {
                verts.put((ox + x * c - y * s) / vw * 2f - 1f); verts.put((oy + x * s + y * c) / vh * 2f - 1f)
                verts.put(u); verts.put(v); verts.put(alpha); verts.put(r); verts.put(gr); verts.put(bl)
            }
            corner(l, b, 0f, g.v1); corner(rt, b, g.u1, g.v1); corner(rt, t, g.u1, g.v0)
            corner(l, b, 0f, g.v1); corner(rt, t, g.u1, g.v0); corner(l, t, 0f, g.v0)
            count++
        }

        /** Draws the quads since [begin]. [bright] scales the (tinted) letters only; the outline stays black. */
        fun draw(bright: Float) {
            if (count == 0 || !hasAtlas) return
            verts.flip()
            GLES30.glUseProgram(prog)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glUniform1f(uBright, bright)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)   // the atlas is premultiplied
            val bytes = STRIDE * 4
            verts.position(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, bytes, verts)
            verts.position(2)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, bytes, verts)
            verts.position(4)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, bytes, verts)
            verts.position(5)
            GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, bytes, verts)
            for (i in 0..3) GLES30.glEnableVertexAttribArray(i)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count * 6)
            for (i in 0..3) GLES30.glDisableVertexAttribArray(i)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    private const val STRIDE = 8               // floats per vertex: x, y, u, v, alpha, r, g, b
    private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aAlpha;
layout(location = 3) in vec3 aTint;
out vec2 vUv;
out float vAlpha;
out vec3 vTint;
void main() { vUv = aUv; vAlpha = aAlpha; vTint = aTint; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
    private const val FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uBright;
in vec2 vUv;
in float vAlpha;
in vec3 vTint;
out vec4 fragColor;
void main() { vec4 t = texture(uTex, vUv); fragColor = vec4(t.rgb * vTint * uBright, t.a) * vAlpha; }
"""
}
