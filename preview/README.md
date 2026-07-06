# Screensaver preview (PC)

A self-contained WebGL sandbox for tweaking the **Aurora**, **Waves**, and **Wheat field**
scenes on this PC, without rebuilding the APK.

> **Aurora is exact, the other two are ports.** Aurora runs the *real* GLSL fragment
> shader from `AuroraRenderer.kt` verbatim, so the preview matches the TV pixel-for-pixel.
> Waves/Wheat are hand-written JS copies of their Kotlin math (see caveat below). The
> **Black hole** scene is not included — it's a full N-body particle simulation (`Sim.kt`),
> which would need porting wholesale to JS.

## Run

Just open `preview/index.html` in any browser (double-click, or):

```powershell
Start-Process .\preview\index.html
```

No build step, no server — it's one HTML file with inline JS.

## What it is

For **Aurora**, all the visual logic lives in the GLSL fragment shader, so the page runs
that shader directly — the hardcoded shader constants are simply exposed as uniforms wired
to the sliders (defaults match `AuroraRenderer.kt`).

For **Waves** and **Wheat field** the visual logic lives in Kotlin (their GLSL shaders are
trivial), so the page is instead a **faithful JS port** of the wave/star math
(`WavesRenderer.kt`) and the stalk/wind math (`WheatFieldRenderer.kt`). Same shaders, same
coordinate mapping, same `dt`-clamped time stepping, so what you see closely matches the TV.

## Workflow

1. Pick a scene tab, drag the sliders, watch it update live.
2. Each slider is labelled with the **Kotlin constant** it maps to.
3. Deeper knobs (sine-component frequency/time multipliers, the wind formula) are plain
   JS constants near the top of each scene object — edit them and refresh.
4. When you like a look, click **Dump current values** and port the numbers back into the
   matching renderer in `app/src/main/java/com/fleece/screensaver/`.

## Caveat — two sources of truth

This sandbox is a **copy** of the math. Changes here do **not** flow into the app
automatically; you must port liked values back into the Kotlin renderers by hand, then
`gradlew installDebug` to see them on the TV.
