package com.fleece.screensaver

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * "Aurora" screensaver: a few semi-transparent curtains of light drifting across a night sky.
 * Everything is computed per-pixel in a single full-screen fragment shader — no geometry, no
 * pre-rendered frames. Each curtain is a soft vertical band whose lower edge undulates via value-
 * noise FBM (the "floating / convulsing" motion) and which is striped by slowly shifting vertical
 * rays. The colour ramps green (bottom) → teal → violet (top) like a real aurora, and the whole
 * thing is deliberately dim and translucent for an elegant, OLED-friendly look.
 *
 * Only two uniforms drive it: elapsed time and resolution. The fragment shader does all the work.
 */
class AuroraRenderer : GLSurfaceView.Renderer {

    private var prog = 0
    private var aPos = 0
    private var uTime = 0
    private var uRes = 0

    private var w = 1f
    private var h = 1f
    private var lastNanos = 0L
    private var t = 0.0

    // Static full-screen triangle strip in NDC.
    private val quad = GlUtil.directFloats(4 * 2)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        prog = GlUtil.link(VS, FS)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
        uRes = GLES20.glGetUniformLocation(prog, "uRes")
        quad.clear()
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f).forEach { quad.put(it) }
        quad.position(0)
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

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(uTime, t.toFloat())
        GLES20.glUniform2f(uRes, w, h)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private const val VS = """
            attribute vec2 aPos;
            void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        // All the artistry lives here. Value-noise FBM gives the curtains their organic, breathing
        // motion; the per-layer loop stacks a handful of translucent veils at different depths.
        private const val FS = """
            precision highp float;
            uniform float uTime;
            uniform vec2 uRes;

            float hash(vec2 p){
                p = fract(p * vec2(233.34, 851.73));
                p += dot(p, p + 23.45);
                return fract(p.x * p.y);
            }
            float noise(vec2 p){
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
            }
            float fbm(vec2 p){
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++){
                    v += a * noise(p);
                    p = p * 2.0 + vec2(7.3, 1.1);
                    a *= 0.5;
                }
                return v;
            }

            void main(){
                vec2 uv = gl_FragCoord.xy / uRes.xy;
                float aspect = uRes.x / uRes.y;
                float x = uv.x * aspect;

                // Night sky: near-black, fading to a faint cold blue toward the top.
                vec3 col = mix(vec3(0.00, 0.01, 0.03), vec3(0.01, 0.02, 0.06), uv.y);

                const int LAYERS = 3;
                for (int l = 0; l < LAYERS; l++){
                    float fl = float(l);
                    float t = uTime * (0.05 + 0.018 * fl) + fl * 11.0;

                    // The lower edge of this curtain undulates as it drifts sideways.
                    float sway = fbm(vec2(x * 0.9 + t, t * 0.4));
                    sway += 0.35 * sin(x * 1.7 + uTime * 0.15 + fl * 2.0);
                    float base = 0.34 + 0.13 * fl + 0.12 * (sway - 0.5);

                    // Soft veil: sharp-ish lower edge, long gentle fade upward.
                    float dy = uv.y - base;
                    float veil = dy > 0.0
                        ? exp(-dy / (0.20 + 0.06 * fl))
                        : exp(dy / 0.015);

                    // Vertical rays, slowly shifting — the streaky structure of a real aurora.
                    float rays = fbm(vec2(x * 7.0 + sway * 1.5, uv.y * 2.5 - uTime * 0.08));
                    rays = 0.45 + 0.55 * rays;

                    // Gentle convulsing: brightness breathes along the curtain over time.
                    float pulse = 0.55 + 0.45 * sin(uTime * 0.25 + x * 1.3 + fl * 2.3);

                    float intensity = veil * rays * pulse;

                    // Colour climbs green -> teal -> violet from the base upward.
                    vec3 lc = mix(vec3(0.10, 0.85, 0.45), vec3(0.15, 0.55, 0.80),
                                  clamp(dy / 0.22, 0.0, 1.0));
                    lc = mix(lc, vec3(0.55, 0.25, 0.75), clamp((dy - 0.18) / 0.25, 0.0, 1.0));

                    col += lc * intensity * 0.32;
                }

                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
