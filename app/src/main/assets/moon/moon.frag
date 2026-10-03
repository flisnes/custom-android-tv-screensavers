#version 300 es
// Moon contour-map scenes. Shared verbatim by preview/moon.html (WebGL2) and MoonRenderer.kt (GL ES 3.0).
// Heights are in metres, in an R16F texture (globe: full equirectangular sphere; zoom: an extracted window).
precision highp float;
precision highp sampler2D;

uniform sampler2D uHeight;
uniform vec2  uRes;          // viewport size (px)
uniform int   uMode;         // 0 = zoom window, 1 = spinning globe, 2 = lit globe

// Mode 0: screen -> (lat, lon) via a local projection around uZoomCenter, then -> uv in the extracted texture.
uniform vec2  uZoomCenter;   // (lat, lon) radians
uniform float uZoomHalfH;    // half of the vertical angular extent on screen (radians)
uniform vec4  uExtract;      // lon0, lat0 (top-left of the extracted texture, radians), dlon, dlat (extent, radians; dlat > 0 going south)
uniform int   uSmooth;       // 1 = bicubic B-spline sampling (hides the source grid when zoomed in)

// Modes 1/2: orthographic globe.
uniform float uRotation;     // longitude offset (radians)
uniform float uTilt;         // north pole tipped toward the camera (radians)
uniform float uGlobeRadius;  // as a fraction of half the viewport height
uniform vec3  uLightDir;     // view space (camera looks down -z), normalised
uniform float uLitSlope;     // slope (rise/run) at which a contour counts as a real slope, not noise
uniform float uLitSoft;      // softness of the lit/unlit split (cosine of angle to the light)
uniform float uLitAmbient;   // brightness of contours on unlit slopes

// Contours.
uniform float uInterval;     // metres between contour levels
uniform float uMajorEvery;   // every Nth level is drawn as a major line
uniform float uLineWidth;    // minor line width (px)
uniform float uMajorWidth;   // major line width (px)
uniform float uMinorBright;
uniform float uMajorBright;
uniform float uSweepH;       // metres: only levels at or below this are drawn
uniform float uRiseRange;    // metres the sweep travels past a level while that line fades in (0 = instant)
uniform float uGlowRange;    // metres below the sweep that still glow
uniform float uGlowBright;   // extra brightness of freshly-swept lines
uniform float uDenseMax;     // cap on the grey level where lines are too dense to resolve
uniform float uFade;         // global multiplier (fade in/out)

out vec4 fragColor;

const float PI = 3.14159265358979;
const float MOON_R = 1737400.0;

float sampleH(vec2 uv) { return texture(uHeight, uv).r; }

// Cubic B-spline via four bilinear taps (C2-continuous: contours stay smooth between source pixels).
float sampleHSmooth(vec2 uv) {
    vec2 ts = vec2(textureSize(uHeight, 0));
    vec2 p = uv * ts - 0.5;
    vec2 f = fract(p);
    vec2 i = floor(p) + 0.5;
    vec2 f2 = f * f, f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    vec2 s0 = w0 + w1, s1 = w2 + w3;
    vec2 o0 = (i - 1.0 + w1 / s0) / ts;
    vec2 o1 = (i + 1.0 + w3 / s1) / ts;
    return (texture(uHeight, vec2(o0.x, o0.y)).r * s0.x + texture(uHeight, vec2(o1.x, o0.y)).r * s1.x) * s0.y
         + (texture(uHeight, vec2(o0.x, o1.y)).r * s0.x + texture(uHeight, vec2(o1.x, o1.y)).r * s1.x) * s1.y;
}

float height(vec2 uv) { return uSmooth == 1 ? sampleHSmooth(uv) : sampleH(uv); }

// Line brightness at height h given the per-pixel contour density. Returns 0..1 (pre-fade).
float contours(float h, float lit) {
    float iv = uInterval;
    float f = h / iv;
    float w = max(fwidth(f), 1e-5);             // contour levels per pixel
    float lvl = floor(f + 0.5);
    float g = abs(f - lvl);                     // distance to nearest level, in levels

    // Where lines are far apart: draw the nearest level as an anti-aliased line of constant pixel width.
    bool major = mod(lvl, uMajorEvery) == 0.0;
    float lw = major ? uMajorWidth : uLineWidth;
    float cov = clamp((0.5 * lw * w - g) / w + 0.5, 0.0, 1.0);
    float base = major ? uMajorBright : uMinorBright;
    float levelH = lvl * iv;
    // A line fades in over uRiseRange metres of sweep travel after the sweep crosses its level (no hard pop),
    // while the glow peaks just after it appears and then decays.
    float vis = smoothstep(0.0, max(uRiseRange, 1e-3), uSweepH - levelH);
    float glow = uGlowBright * exp(-max(uSweepH - levelH, 0.0) / uGlowRange);
    float sparse = cov * vis * min(base + glow, 1.0);

    // Where lines are too dense to resolve (steep slopes, the globe's limb): box-filtered average instead of aliasing.
    float avgCov = min(uLineWidth * w, uDenseMax);
    float visD = smoothstep(-0.5 * w * iv, max(w * iv, uRiseRange), uSweepH - h);
    float glowD = uGlowBright * exp(-max(uSweepH - h, 0.0) / uGlowRange);
    float dense = avgCov * visD * min(uMinorBright + glowD, 1.0);

    float dens = smoothstep(0.4, 0.9, w);
    return mix(sparse, dense, dens) * lit;
}

void main() {
    vec2 px = gl_FragCoord.xy;
    float c = 0.0;

    if (uMode == 0) {
        vec2 s = (px - 0.5 * uRes) / (0.5 * uRes.y);        // y in -1..1, x in -aspect..aspect
        float lat = uZoomCenter.x + s.y * uZoomHalfH;
        float lon = uZoomCenter.y + s.x * uZoomHalfH / cos(lat);
        vec2 uv = vec2((lon - uExtract.x) / uExtract.z, (uExtract.y - lat) / uExtract.w);
        c = contours(height(uv), 1.0);
    } else {
        float Rpx = uGlobeRadius * 0.5 * uRes.y;
        vec2 d = (px - 0.5 * uRes) / Rpx;
        float r2 = dot(d, d);
        float edge = clamp((1.0 - sqrt(r2)) * Rpx + 0.5, 0.0, 1.0);   // anti-aliased disc
        if (edge > 0.0) {
            vec3 n = vec3(d, sqrt(max(1.0 - r2, 0.0)));          // view-space unit normal on the sphere
            // Body frame: tip the pole toward the camera by uTilt (rotate about x).
            float ct = cos(uTilt), st = sin(uTilt);
            vec3 b = vec3(n.x, n.y * ct - n.z * st, n.y * st + n.z * ct);
            float lat = asin(clamp(b.y, -1.0, 1.0));
            float lon = atan(b.x, b.z) + uRotation;
            vec2 uv = vec2(lon / (2.0 * PI), 0.5 - lat / PI);

            float lit = 1.0;
            if (uMode == 2) {
                vec2 ts = vec2(textureSize(uHeight, 0));
                vec2 e = 1.0 / ts;
                float hE = sampleH(uv + vec2(e.x, 0.0)), hW = sampleH(uv - vec2(e.x, 0.0));
                float hS = sampleH(uv + vec2(0.0, e.y)), hN = sampleH(uv - vec2(0.0, e.y));
                float mPerTexLon = 2.0 * PI * MOON_R * max(cos(lat), 0.05) / ts.x;
                float mPerTexLat = PI * MOON_R / ts.y;
                float dE = (hE - hW) / (2.0 * mPerTexLon);          // uphill slope towards east
                float dN = (hN - hS) / (2.0 * mPerTexLat);          // uphill slope towards north
                vec3 axis = vec3(0.0, ct, -st);                      // body north pole in view space
                vec3 east = normalize(cross(axis, n));
                vec3 north = cross(n, east);
                vec3 grad = east * dE + north * dN;                  // uphill direction in the tangent plane
                float gm = length(grad);
                vec3 lt = uLightDir - n * dot(n, uLightDir);         // light projected into the tangent plane
                float ltm = length(lt);
                // A slope catches the light when it rises away from the light (its normal tips toward it).
                float cosA = -dot(grad, lt) / max(gm * ltm, 1e-6);
                float dir = smoothstep(-uLitSoft, uLitSoft, cosA);
                float mag = smoothstep(0.0, uLitSlope, gm);
                float day = smoothstep(-0.1, 0.35, dot(n, uLightDir));
                lit = mix(uLitAmbient, 1.0, dir * mag) * day;
            }
            c = contours(height(uv), lit) * edge;
        }
    }
    fragColor = vec4(vec3(c * uFade), 1.0);
}
