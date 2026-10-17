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

## Moon contour scenes — `preview/moon.html`

A second page for the three **moon** scenes (zoom sweep, globe sweep, lit globe). Unlike the
ports above it is **exact**: it runs `app/src/main/assets/moon/moon.frag` verbatim (WebGL2) on
the same height-map assets the APK ships, and the JS sweep/window logic mirrors
`MoonRenderer.kt` line for line.

It needs a web server (it fetches the shader and ~130 MB of height data), so from the repo root:

```powershell
python -m http.server 8765
Start-Process http://127.0.0.1:8765/preview/moon.html
```

Generate the assets first if `app/src/main/assets/moon/*.bin` are missing (see
`docs/screensaver-setup.md` §4c / `tools/moon_data.py`).

- Tabs switch scene; every slider is named after the constant in `MoonRenderer.kt`.
- `H` hides the panel, `N` picks the next zoom window, `R` restarts the sweep, `Space` pauses.
- **Dump values** copies the current numbers as JSON to port back into the Kotlin defaults.
- URL parameters give deterministic frames (handy for screenshots):
  `?scene=zoom|globe|lit&seed=3&t=20` (fixed sim time), `&lat=-43&lon=349&hh=6` (force a zoom
  window, degrees), `&sweep=0.5` (force sweep progress), `&panel=0`, and `&p.<param>=<value>`
  for any slider (e.g. `&p.interval=750&p.globeBlur=2`), and `&body=earth` for the Earth twins of the
  scenes (ETOPO data from `app/src/main/assets/earth/`, see `tools/earth_data.py`), `&earthColors=1`
  (`&snowLine=4000`) for the Earth colours, `&craterNames=1` for crater names on the moon zoom (a 2D-canvas
  mirror of `CraterLabels.kt`), `&fill=0.55` for the Earth zoom's band fill (colours as `&seaShallow=3F86D0`
  etc., hex, named like the TV tweaks). The country/sea and landmark names (`MapLabels.kt`) are TV-only; the
  page's old single place name can still be shown with `&placeNames=1`.

Line widths are in 1080p pixels; the renderer scales them with the surface height, so a 4K
surface draws proportionally thicker lines (and resolves far more contours before they merge
into grey).
