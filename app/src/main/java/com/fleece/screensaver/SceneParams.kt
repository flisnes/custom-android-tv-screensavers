package com.fleece.screensaver

/**
 * The tweakable parameters of each scene (shown in the preview's tweak panel). The defaults here
 * ARE the scene's look: to adopt a tweaked value as the new default, change it here.
 */
object SceneParams {

    /** Appended to every scene. */
    val common = listOf(
        Param("panelBrightness", "Panel brightness", 0.45f, 0.1f, 1f, 0.05f,
            "Screen brightness while this scene runs (dimmed for OLED safety).", "Screen"),
    )

    val blackHole = listOf(
        Param("particles", "Particles", 2800f, 500f, 8000f, 100f, "Number of particles (restarts the scene).", "Simulation", restart = true),
        Param("speed", "Simulation speed", 1f, 0.1f, 4f, 0.1f, "Time scale of the orbits.", "Simulation"),
        Param("diskRespawn", "Disk refill fraction", 0.85f, 0f, 1f, 0.05f,
            "Share of lost particles that return to the flat disk; the rest fall in as inclined streams.", "Simulation"),
        Param("accretion", "Accretion rate", 1f, 0f, 10f, 0.25f, "How fast disk matter drains into the hole (multiplier).", "Simulation"),
        Param("tilt", "Disk tilt", 72f, 0f, 89f, 1f, "Viewing angle in degrees: 0 = face-on, 89 = edge-on.", "View"),
        Param("scale", "Size", 0.8f, 0.3f, 1.6f, 0.05f, "Scene size relative to the screen height.", "View"),
        Param("wander", "Screen drift", 1f, 0f, 2f, 0.1f, "How far the scene wanders around the screen (OLED safety).", "View"),
        Param("brightness", "Particle brightness", 0.72f, 0.1f, 1.5f, 0.02f, "", "Look"),
        Param("maxAlpha", "Particle opacity cap", 0.5f, 0.05f, 1f, 0.05f, "Upper limit on a single particle's opacity.", "Look"),
        Param("particleSize", "Particle size", 2.6f, 0.5f, 8f, 0.1f, "Point size in pixels (scale).", "Look"),
        Param("trailFade", "Trail fade per frame", 0.18f, 0.01f, 1f, 0.01f, "Lower = longer motion trails.", "Look"),
        Param("ringBright", "Photon ring brightness", 1f, 0f, 3f, 0.1f, "", "Look"),
        Param("ringSpeed", "Ring hotspot speed", 1f, 0f, 5f, 0.1f, "", "Look"),
        Param("glowBright", "Back-glow brightness", 1f, 0f, 3f, 0.1f, "", "Look"),
    )

    val flowField = listOf(
        Param("particles", "Particles", 2600f, 500f, 8000f, 100f, "Number of particles (restarts the scene).", "Particles", restart = true),
        Param("speed", "Particle speed", 1f, 0.1f, 4f, 0.1f, "", "Particles"),
        Param("lifetime", "Particle lifetime", 1f, 0.2f, 4f, 0.1f, "Multiplier on how long a particle lives before respawning.", "Particles"),
        Param("alpha", "Particle brightness", 0.26f, 0.02f, 1f, 0.02f, "", "Particles"),
        Param("size", "Particle size", 3f, 0.5f, 10f, 0.5f, "", "Particles"),
        Param("trailFade", "Trail fade per frame", 0.045f, 0.005f, 0.5f, 0.005f, "Lower = longer ribbons.", "Particles"),
        Param("swirlScale", "Swirl density", 1f, 0.3f, 3f, 0.1f, "Higher = smaller, more numerous swirls.", "Current"),
        Param("twist", "Twistiness", 1f, 0.2f, 3f, 0.1f, "How strongly the current turns.", "Current"),
        Param("turbulence", "Turbulence", 0.7f, 0f, 3f, 0.1f, "Weight of the fine diagonal ripples.", "Current"),
        Param("evolve", "Current change speed", 1f, 0f, 5f, 0.1f, "How fast the current pattern drifts over time.", "Current"),
        choiceParam("palette", "Palette", 0, listOf("Aurora", "Ice", "Ember", "White"), "", "Current"),
    )

    /**
     * Shared by the Moon and Earth contour scenes ([MoonRenderer]). Earth: busier land relief at the zoom scale
     * (smoother, sparser lines) and gentler slopes per texel on the larger globe (lower lit threshold).
     */
    fun moon(mode: MoonRenderer.Mode, body: MoonRenderer.Body = MoonRenderer.Body.MOON): List<Param> {
        val earth = body == MoonRenderer.Body.EARTH
        val lit = mode == MoonRenderer.Mode.LIT
        val zoom = mode == MoonRenderer.Mode.ZOOM
        val pulse = mode == MoonRenderer.Mode.PULSE
        val sweep = if (pulse) listOf(
            Param("pulseSpeed", "Pulse speed (m/s)", 1200f, 100f, 5000f, 100f,
                "Metres of elevation a pulse front climbs (or descends) per second.", "Pulse"),
            Param("pulseSpacing", "Pulse spacing (m)", 5000f, 1000f, 20000f, 500f,
                "Elevation between successive pulse fronts; the " + (if (earth) "Earth globe data spans about 15 000 m, deepest trench to the Himalaya."
                    else "Moon spans about 20 000 m."), "Pulse"),
            choiceParam("direction", "Pulse direction", 0, listOf("Uphill", "Downhill"),
                "Uphill: pulses start in the basins and climb toward the peaks.", "Pulse"),
            Param("glowBright", "Pulse brightness", 1f, 0f, 2f, 0.05f, "Extra brightness of a line as a pulse passes.", "Pulse"),
            Param("pulseTrail", "Pulse trail (m)", 1500f, 100f, 10000f, 100f,
                "How far behind a front lines keep glowing (elevation).", "Pulse"),
            Param("lineFadeSec", "Pulse fade-in (s)", 1f, 0f, 3f, 0.1f,
                "Seconds a line takes to brighten as a front reaches it.", "Pulse"),
        ) else listOf(
            Param("sweepSec", "Sweep duration (s)", 30f, 5f, 240f, 1f,
                "Seconds for the sweep to travel from the lowest to the highest point.", "Sweep"),
            choiceParam("direction", "Sweep direction", 0, listOf("Low to high", "High to low"),
                "Low to high: basins are drawn first and peaks last.", "Sweep"),
            Param("holdSec", "Hold when complete (s)", 5f, 0f, 120f, 1f, "", "Sweep"),
            Param("fadeSec", "Fade out (s)", 2.5f, 0.5f, 10f, 0.5f, "", "Sweep"),
            Param("lineFadeSec", "Line fade-in (s)", 0.6f, 0f, 3f, 0.1f,
                "Seconds each new line takes to fade in. Lines are drawn at a steady rate: the sweep time is " +
                    "shared equally between all contour levels.", "Sweep"),
            Param("glowBright", "New-line glow", 0.9f, 0f, 2f, 0.05f, "Extra brightness of freshly drawn lines.", "Sweep"),
            Param("glowLevels", "Glow length", 2.5f, 0.25f, 10f, 0.25f, "How many levels behind the sweep still glow.", "Sweep"),
        )
        val height = listOf(
            Param("tint", "Colour by height", 0f, 0f, 1f, 0.1f,
                "Tints lines by elevation: deep = blue, middle = white, high = amber.", "Height"),
            Param("lowDim", "Dim low lines", 0f, 0f, 0.95f, 0.05f,
                "Darkens lines toward the lowest elevation, so peaks stand out brightest.", "Height"),
        ) + if (earth) listOf(
            toggleParam("earthColors", "Earth colours", false,
                "Sea-floor lines blue, land green, lines above the snow line white (overrides Colour by height).", "Height"),
            Param("snowLine", "Snow line at equator (m)", 4000f, 500f, 8000f, 250f,
                "Lines above this are white. It drops toward the poles (about half at 50°, so the Alps get white tips " +
                    "and the Greenland and Antarctic ice sheets are white).", "Height"),
        ) else emptyList()
        val interval = if (zoom) listOf(
            Param("autoDensity", "Contour density", if (earth) 0.15f else 0.3f, 0.05f, 1f, 0.05f,
                "Zoom picks a contour interval per window; higher = more, closer lines.", "Lines"),
            Param("minInterval", "Minimum interval (m)", 50f, 10f, 500f, 10f, "", "Lines"),
        ) else listOf(
            Param("interval", "Contour interval (m)", if (lit) 800f else if (pulse) 1000f else 2000f, 100f, 5000f, 100f,
                "Metres of elevation between neighbouring lines.", "Lines"),
        )
        val lines = interval + listOf(
            Param("majorEvery", "Major line every", 5f, 1f, 20f, 1f, "Every Nth level is drawn thicker and brighter.", "Lines"),
            Param("lineWidth", "Line width (px)", 1.4f, 0.5f, 5f, 0.1f, "", "Lines"),
            Param("majorWidth", "Major line width (px)", 2.2f, 0.5f, 6f, 0.1f, "", "Lines"),
            Param("minorBright", "Line brightness", if (lit) 0.7f else if (pulse) 0.15f else 0.45f, 0f, 1f, 0.05f,
                if (pulse) "Resting brightness between pulses (0 = lines only show while a pulse passes)." else "", "Lines"),
            Param("majorBright", "Major line brightness", if (lit) 1f else if (pulse) 0.3f else 0.8f, 0f, 1f, 0.05f, "", "Lines"),
            Param("denseMax", "Dense area grey cap", 0.35f, 0f, 1f, 0.05f,
                "Brightness cap where lines are too close to draw separately (steep slopes, globe edge).", "Lines"),
        )
        val view = if (zoom) listOf(
            Param("windowMinDeg", "Window size min (°)", 5f, 1f, 30f, 0.5f, "Half-height of the view; applies from the next window.", "View"),
            Param("windowMaxDeg", "Window size max (°)", 9f, 1f, 30f, 0.5f, "", "View"),
            Param("driftZoom", "Zoom drift", 0.08f, 0f, 0.3f, 0.01f, "How much the view zooms in during a cycle.", "View"),
            Param("driftPan", "Pan drift", 0.03f, 0f, 0.1f, 0.01f, "How far the view pans during a cycle.", "View"),
            Param("windowBlur", "Terrain smoothing", if (earth) 1.6f else 0.8f, 0f, 3f, 0.1f, "Blur of the height data (source pixels); next window.", "View"),
        ) else listOf(
            Param("revSec", "Rotation period (s)", if (lit) 420f else 360f, 30f, 1800f, 30f, "", "View"),
            Param("tiltDeg", "Tilt (°)", 18f, -60f, 60f, 2f, "North pole tipped toward the viewer.", "View"),
            Param("tiltWobbleDeg", "Tilt wobble (°)", 12f, 0f, 45f, 1f, "", "View"),
            Param("globeRadius", "Globe size", 0.86f, 0.3f, 1.5f, 0.02f, "Radius relative to half the screen height.", "View"),
        )
        val light = if (lit) listOf(
            toggleParam("litSweep", "Sweep in lit mode", false, "Off = all lines always shown.", "Light"),
            Param("lightAzDeg", "Light direction (°)", -45f, -180f, 180f, 5f, "", "Light"),
            Param("lightElDeg", "Light elevation (°)", 55f, 0f, 90f, 5f, "", "Light"),
            Param("litSlope", "Slope threshold", if (earth) 0.01f else 0.03f, 0.005f, 0.2f, 0.005f, "Slopes gentler than this count as flat.", "Light"),
            Param("litSoft", "Lit/unlit softness", 0.3f, 0.05f, 1f, 0.05f, "", "Light"),
            Param("litAmbient", "Unlit line brightness", 0.04f, 0f, 0.5f, 0.02f, "", "Light"),
        ) else emptyList()
        val names = if (zoom && !earth) listOf(
            toggleParam("craterNames", "Crater names", false, "Label the larger named craters in view (IAU names); each name appears with its crater's floor contour.", "Names"),
            Param("labelMax", "Max names", 8f, 1f, CraterLabels.MAX_LABELS.toFloat(), 1f,
                "Most names per window (largest craters first, overlapping names skipped). From the next window.", "Names"),
            Param("labelMinSize", "Smallest named crater", 0.05f, 0.01f, 0.3f, 0.01f,
                "Crater diameter as a fraction of the view height. From the next window.", "Names"),
            Param("labelSize", "Name size (px)", 20f, 10f, 40f, 1f, "From the next window.", "Names"),
            Param("labelBright", "Name brightness", 0.55f, 0.1f, 1f, 0.05f, "", "Names"),
        ) else if (zoom) listOf(
            toggleParam("placeNames", "Place name", true,
                "Name the country in view at the bottom of the screen (the sea, if there is little land).", "Names"),
            Param("placeSize", "Name size (px)", 26f, 12f, 48f, 1f, "From the next window.", "Names"),
            Param("placeBright", "Name brightness", 0.75f, 0.1f, 1f, 0.05f, "", "Names"),
        ) else emptyList()
        return sweep + height + lines + view + light + names
    }
}
