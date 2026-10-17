# The Fleece black-hole screensaver (install, Philips-logo removal, and reverting)

This documents exactly how the custom black-hole screensaver was installed on the
Philips TV, registered as the system screensaver, how the Philips logo screensaver
was suppressed, and how to undo every step.

Everything is done over **adb on the local network** from the dev PC. Nothing here
requires root, and every step is reversible.

## Device

| | |
|---|---|
| Model | Philips 65OLED705 (`TPM191E`) |
| OS | Android TV / Google TV 12 (API 31) |
| adb address | `192.168.10.40:5555` (DHCP — it was `192.168.10.115` on 2026-10-03; see below if it moved) |
| Our screensaver | `com.fleece.screensaver` (DreamService `.BlackHoleDreamService`) |
| Stock Android dream | `com.google.android.backdrop/.Backdrop` (Google photo/clock) |
| Philips logo screensaver | `org.droidtv.tvsystemui` (`.logodreams.*`, a firmware overlay) |

`adb` lives in the Android SDK platform-tools
(`C:\Users\Haakon\AppData\Local\Android\Sdk\platform-tools\adb.exe`); the examples
below assume `adb` is on your PATH.

> The screensaver is a **standalone Android project** at `android-tv-launcher/screensaver/`
> (its own git repo; git-ignored from the launcher repo). It has no launcher
> activity — it only registers a `DreamService`.

## Prerequisites (one-time, on the TV)

1. Enable **Developer options** and **Network debugging** on the TV.
2. Authorize the dev PC (accept "Allow USB debugging", *Always allow from this computer*).
3. Leave network debugging on — it survives reboots and is your safety line.

```bash
adb connect 192.168.10.40:5555
adb devices            # should list 192.168.10.40:5555  device
```

The TV's address is handed out by DHCP and has changed before. If `adb connect` times out,
find the host listening on the adb port (Git Bash):

```bash
for i in $(seq 1 254); do ( timeout 2 bash -c "echo >/dev/tcp/192.168.10.$i/5555" 2>/dev/null && echo "open: 192.168.10.$i" ) & done; wait
```

To start the screensaver on demand instead of waiting for the idle timeout:

```bash
adb shell am start -n com.android.systemui/.Somnambulator   # "start now"
adb shell input keyevent KEYCODE_WAKEUP                      # stop it
```

## What was done

### 1. Install the screensaver app

```bash
# from the screensaver/ project root
./gradlew.bat -p . :app:assembleDebug
adb -s 192.168.10.40:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

The app is a single `DreamService` (`BlackHoleDreamService`) that hosts a GLSurfaceView
rendering the black-hole simulation (`BlackHoleRenderer` + `Sim`). It dims the panel
(`screenBrightness = 0.45`) and wanders slowly for OLED safety.

### 2. Register it as the system screensaver

```bash
adb -s 192.168.10.40:5555 shell settings put secure screensaver_enabled 1
adb -s 192.168.10.40:5555 shell settings put secure screensaver_components \
  com.fleece.screensaver/com.fleece.screensaver.BlackHoleDreamService
adb -s 192.168.10.40:5555 shell settings put secure screensaver_activate_on_sleep 1
adb -s 192.168.10.40:5555 shell settings put secure screensaver_activate_on_dock 1
```

### 3. Make it actually start on idle (the timeout trap)

Being registered is **not enough** to make the dream auto-start — the OS only dreams
after an idle timeout, and on this TV both timeouts were set to ~7 days. They also
must not be equal/short together, or the panel just **sleeps to black** instead of
dreaming. The working combination:

```bash
# Start the screensaver after 5 min idle...
adb -s 192.168.10.40:5555 shell settings put system screen_off_timeout 300000
# ...but effectively never put the device to sleep, so the dream keeps running.
adb -s 192.168.10.40:5555 shell settings put secure sleep_timeout 604800000
```

`screen_off_timeout` = when the screensaver **starts**; `sleep_timeout` = when the
device would **sleep** (go black). Change the 5-minute start delay by editing
`screen_off_timeout` (milliseconds).

### 4. Suppress the Philips logo screensaver

The TV's firmware has its **own** screensaver (the bouncing Philips logo) that, after
a longer fixed idle, draws as an **overlay window on top of** whatever dream is
running. It lives in the protected `org.droidtv.tvsystemui` package and is fired by
that package's own internal alarm, so it can't be disabled with `pm disable`,
`setprop`, or any settings toggle (all are locked down on a non-rooted unit).

Because it draws as an **overlay**, the fix is to revoke `tvsystemui`'s
"display over other apps" permission:

```bash
adb -s 192.168.10.40:5555 shell appops set org.droidtv.tvsystemui SYSTEM_ALERT_WINDOW ignore
```

After this the Philips screensaver's timer still fires internally (you may see a brief
notification), but with no overlay permission it **cannot draw the logo** — so the
black hole stays on screen. The volume / source on-screen displays still work.

On-screen equivalent (does the same thing): **Settings → System → Storage →
Internal shared storage → Apps → Special app access → Display over other apps →**
turn **off** "Tv System UI".

### 4b. Scenes (random rotation) and the Fleece Screensavers app

The dream picks a **random scene** each time it activates (Android allows only one
registered screensaver, so rotation happens inside the app). Which scenes are in the
rotation is chosen in the **Fleece Screensavers** app on the TV home screen (also reachable
from the settings entry of the screensaver picker). Each scene has a checkbox and a
**Preview** button that runs it full screen until Back is pressed. The choice is stored in
the app's preferences and read on the next activation; at least one scene must stay
checked, and **Reset to defaults** restores the default set. The default set is black hole,
flow field, embers and the three moon scenes; wheat field, murmuration, vortex ocean,
galaxy and ink start unchecked. The scene list lives in `SceneCatalog.kt`.

Each activation logs its choice and any tweaked values, which is handy when checking the rotation:

```bash
adb -s 192.168.10.115:5555 logcat -s FleeceDream:I   # "scene moon_globe", "tweaks moon_globe: sweepSec=45 tint=0.8"
```

**Tweaking a scene.** In a scene's preview (**Preview / tweak**), **OK** opens a panel of that
scene's parameters: Up/Down picks one, Left/Right changes it live (hold to speed up), OK resets
it, Back closes the panel. Changed values are shown in amber, saved at once, and used by the
screensaver from its next start; the scene list shows how many settings each scene has tweaked.
**Up** (panel closed) toggles an info overlay: frame rate and slowest frame, plus for the moon
scenes the sweep phase, current sweep height, elevation range, contour interval, and how many
seconds pass between new lines. Most parameters exist for the black hole, flow field and the moon
scenes (panel brightness exists for all); the defaults live in `SceneParams.kt`.

Tweaked values are stored only when they differ from the default, so they can be read back to
adopt them as new defaults (copy them into `SceneParams.kt`):

```bash
adb -s 192.168.10.115:5555 shell run-as com.fleece.screensaver cat shared_prefs/fleece_tweaks.xml
# <float name="moon_globe.sweepSec" value="45.0" />  ->  scene key . parameter key
```

Scene indices for the adb override below: `0` black hole, `1` flow field, `2` wheat field,
`3` murmuration, `4` vortex ocean, `5` galaxy, `6` ink in water, `7` embers, `8` moon zoom
contours, `9` moon globe contours, `10` moon lit globe, `11` moon globe sweep (pulses), `12` earth
zoom contours, `13` earth globe contours, `14` earth lit globe, `15` earth globe sweep (pulses). The override pins a scene even if it
is unchecked in the app. (Starry waves, aurora, and grass field were removed earlier; the
indices shifted then, so an old pinned `fleece_scene` value may point at a different scene.)
To pin one (testing or preference):

```bash
adb -s 192.168.10.40:5555 shell settings put system fleece_scene 3   # always murmuration
adb -s 192.168.10.40:5555 shell settings delete system fleece_scene  # back to random
```

### 4c. Moon contour scenes (8, 9, 10, 11) and their height-map data

The four moon scenes (`MoonRenderer.kt`, GL ES 3.0) draw topographic contour lines of
the real lunar surface from NASA's LRO **LOLA** laser-altimeter height model:

- `8` **zoom** — a random window of the surface (the flattest of 3 random candidates is dropped, and windows keep at least 1.5 half-heights away from the last 20 shown); a sweep rises from
  the lowest to the highest point drawing each contour as it passes, holds, fades, repeats.
  The contour interval adapts per window (maria get ~50 m, highlands a few hundred).
- `9` **globe** — slowly spinning orthographic globe, 2000 m contours, same continuous sweep.
- `10` **lit globe** — spinning globe with a light behind the camera, upper-left: only the
  contour segments on slopes facing the light are drawn (an engraved/hatched look).
- `11` **globe sweep** — the spinning globe with every line always drawn dimly while pulses of
  light run endlessly through the elevations (uphill by default): a front every *spacing* metres
  of elevation, moving *speed* m/s, each line brightening as a front reaches it and fading over
  the *trail*. No sweep/hold/fade cycle. Shader: `uPulse` in `moon.frag`.

**Crater names** (zoom scene, tweak group *Names*, off by default): the largest named craters in
the window are labelled, up to *Max names*, skipping craters smaller than *Smallest named crater*
(fraction of the view height) and names that would overlap. Names follow the zoom/pan drift; each
fades in together with the lowest contour on its crater floor (the floor height is found in the
window's height field) and fades out with the scene (`CraterLabels.kt`). The list is
`app/src/main/assets/moon/craters.tsv` (1615 IAU-named craters, committed, 46 KB), generated by
`tools/moon_names.py` from the USGS/IAU nomenclature shapefile
(`https://asc-planetarynames-data.s3.us-west-2.amazonaws.com/MOON_nomenclature_center_pts.zip`).

**Map names** (Earth zoom scene, tweak group *Names*, both kinds on by default, `MapLabels.kt`):
- *Country & sea names*: every country, ocean or sea covering at least 5 % of the view (up to *Max*) is
  named at the point of its visible area farthest from its edges and the screen's, in large spaced
  capitals (seas in italics). The lookup is `app/src/main/assets/earth/places.grid` + `places.tsv`
  (a 0.25° grid of 242 countries and 114 seas, committed, 36 KB), generated by `tools/earth_names.py`
  from Natural Earth 1:50m admin-0 countries and marine polygons.
- *Landmark names*, smaller: peaks and depressions (a triangle marks the spot), mountain ranges,
  plateaus, deserts, rivers, lakes, canyons, straits, fjords, reefs, ocean trenches and ridges. Lines
  (rivers, trenches, ridges, long ranges) are named along their longest visible stretch. The most
  prominent come first, a repeated kind is penalised, and no name may overlap another. The list is
  `app/src/main/assets/earth/landmarks.tsv` (about 3000 entries, committed, 330 KB), generated by
  `tools/earth_landmarks.py` from Natural Earth 1:10m layers plus a curated list of trenches, ridges,
  reefs and canyons (trench and ridge axes are snapped to the ETOPO deepest / highest points).
- Every name fades in as the sweep reaches the ground under it; a country's with its coastline.

**Fill** (Earth zoom scene, tweak group *Fill*): each band between two contour levels is filled with
one colour, sea floor from *shallows* through *middle* to *deepest* (reached at *Sea: deepest colour
at*, 6000 m), land from *lowlands* through *middle* to *peaks* (at *Land: peak colour from*, 5000 m).
The middle colours default to halfway between the ends. 0 m is always a contour level (levels are
multiples of the interval), so the coastline is a line and no band straddles it. The bands flood in
with the sweep. Colours are picked in the tweak panel with Left/Right from a 78-colour palette.
*Fill between lines* is the fill's opacity (0 = the classic black background). Shader: `fillColor`
in `moon.frag`. Cost on the TV: about 24 -> 20 fps.

**Lettering** for all names (`MapText.kt`): Jost Medium (`app/src/main/assets/fonts`, SIL OFL, a
Futura revival; Futura is the typeface of the Apollo 11 plaque), white letters with a black outline
around the glyphs so they stay readable over contour lines. *Name brightness* dims the letters only.

The height maps are **not in git** (~130 MB). Regenerate them once before building:

```bash
# download (public domain, NASA/GSFC; 33 MB + 530 MB)
curl -L -o LDEM_16.IMG https://imbrium.mit.edu/DATA/LOLA_GDR/CYLINDRICAL/IMG/LDEM_16.IMG
curl -L -o LDEM_64.IMG https://imbrium.mit.edu/DATA/LOLA_GDR/CYLINDRICAL/IMG/LDEM_64.IMG
python tools/moon_data.py <dir containing the two IMG files>
# -> app/src/main/assets/moon/ldem_16.bin (globe), ldem_32.bin (zoom, lat ±64°), moon.json
```

They are stored uncompressed in the APK (`noCompress "bin"`) so the zoom scene can
memory-map the 32 px/deg grid and read only the window it needs. The APK is ~160 MB;
`adb install` over Wi-Fi takes about half a minute. The app declares `largeHeap` because the
globe scenes briefly hold the 66 MB float height field in the Java heap while uploading it
(the TV's normal heap limit is 192 MB).

Verified on the TV on 2026-10-03: Mali-G51, OpenGL ES 3.2, the dream surface is 1920x1080
(the panel upscales), all three scenes render without errors.

The look is tuned on the TV with the tweak panel (see 4b). `preview/moon.html` runs the *same*
fragment shader (`app/src/main/assets/moon/moon.frag`) in the browser against the same data — see
`preview/README.md` — but has no controls for the height colouring, low-line dimming or sweep
direction added for the TV.

Line timing: the sweep time is shared equally between all contour levels in the scene (a constant
number of levels per second, from the lowest to the highest point), and each new line fades in
over **Line fade-in (s)**. An earlier area-based pacing lingered on common elevations and rushed
through the rare high peaks at the end, several levels per frame; it was removed on 2026-10-09.

### 4d. Earth contour scenes (12, 13, 14, 15) and their height-map data

The same four scenes on the Earth (same `MoonRenderer.kt` and `moon.frag`, `Body.EARTH`), from
NOAA **ETOPO 2022** 60 arc-second relief: land topography *and* ocean bathymetry in one grid, ice
surface on Greenland/Antarctica. Sea level is simply the 0 m contour, so the coastline is always a
major line. Scene order and defaults mirror the moon (`12` zoom, `13` globe, `14` lit, `15` pulse);
each has its own tweak values (`earth_*` keys). Earth-only defaults: the zoom blurs more (1.6 px) and
aims for sparser lines (density 0.15), because land relief at 3.7 km/px is speckly (Norway turned to grey
noise at the moon settings); the lit globe uses a 0.01 slope threshold since Earth slopes per texel
are gentler.

The source (466 MB GeoTIFF, 60 px/deg) is far finer than the screen can show, so the generator
box-downsamples it so the Earth never costs more than the Moon on the TV:

| grid | Moon | Earth |
|---|---|---|
| globe (Java heap while uploading, as floats) | 16 px/deg, 5760x2880, 33 MB file / 66 MB heap | 12 px/deg, 4320x2160, 19 MB / 37 MB |
| zoom (memory-mapped from the APK) | 32 px/deg, lat ±64°, 94 MB | 30 px/deg, lat ±72° (reaches northern Norway), 93 MB |

At 1080p the globe spans only ~5 screen px per degree and the moon grid is pre-blurred to ~0.125°
anyway, so 12 px/deg with the same 0.125° blur looks the same. Earth zoom windows are centred
within ±60° (grid limit minus 12°, as the moon's ±52°).

```bash
curl -L -o ETOPO_2022_v1_60s_N90W180_surface.tif   https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO2022/data/60s/60s_surface_elev_gtif/ETOPO_2022_v1_60s_N90W180_surface.tif
pip install tifffile imagecodecs
python tools/earth_data.py <dir containing the tif>
# -> app/src/main/assets/earth/etopo_12.bin (globe), etopo_30.bin (zoom), earth.json
```

The Earth data adds ~112 MB to the APK (debug APK 161 -> 255 MB). Only one scene's data is loaded at a time,
so heap/RAM use is unchanged; the cost is flash storage on the TV and install time. If storage
gets tight, lower `ZOOM_PPD`/`LAT_LIMIT` in `tools/earth_data.py` (e.g. 20 px/deg halves the zoom file).
Preview in the browser with `preview/moon.html?body=earth`.

**Earth colours** (tweak group *Height*, off by default): each contour gets one colour from its
level: below 0 m blue (sea floor), 0 m and up green (the coastline counts as land), above the snow
line white. The snow line (*Snow line at equator*, default 4000 m) drops with latitude as
cos(lat)^1.5 (about 2300 m at the Alps, a few hundred metres in Greenland/Antarctica), so mountain
tips and the ice sheets turn white. It overrides *Colour by height*. Shader: `uEarthCol`, `uSnowLine`.

### 5. Verify

```bash
# Registered screensaver should be ours
adb -s 192.168.10.40:5555 shell settings get secure screensaver_components

# Overlay permission for tvsystemui should read "ignore"
adb -s 192.168.10.40:5555 shell appops get org.droidtv.tvsystemui SYSTEM_ALERT_WINDOW

# Preview the dream now (component name may vary by build)
adb -s 192.168.10.40:5555 shell am start -n com.android.systemui/.Somnambulator

# Reproduce the Philips trigger while idle — with the fix in place the logo must NOT appear
adb -s 192.168.10.40:5555 shell am broadcast -a org.droidtv.intent.action.START_SCREENSAVER
```

> Note: `adb screencap` **can** capture the GL screensaver (unlike the older WebView
> build). The Philips logo frame is near-black with a small centered logo.

## How to revert

Each piece is independent; revert only what you want.

```bash
# 1. Restore the Philips logo screensaver (re-grant the overlay permission)
adb -s 192.168.10.40:5555 shell appops set org.droidtv.tvsystemui SYSTEM_ALERT_WINDOW allow

# 2. Restore the stock Android dream (Google photos/clock)
adb -s 192.168.10.40:5555 shell settings put secure screensaver_components \
  com.google.android.backdrop/.Backdrop
#   ...or pick another in Settings -> System -> Screen saver.

# 3. Restore default idle timing (optional; values below are typical, adjust to taste)
adb -s 192.168.10.40:5555 shell settings put system screen_off_timeout 300000
adb -s 192.168.10.40:5555 shell settings put secure sleep_timeout 900000

# 4. Remove the screensaver app entirely
adb -s 192.168.10.40:5555 shell pm uninstall com.fleece.screensaver
```

A **factory reset** also restores everything (the Philips screensaver and stock dream
were only suppressed/repointed, never removed).

## Persistence & gotchas

- `appops` and the `settings` values **persist across reboots**. A full firmware
  update *might* reset the `appops` permission; if the Philips logo ever returns,
  just re-run step 4.
- Disabling `tvsystemui`'s overlay permission is the **only** non-root way to stop the
  Philips logo. Dead ends that do **not** work (don't bother): `pm disable`/
  `disable-user` on the `logodreams` components (SecurityException — protected pkg),
  `setprop persist.sys.SMTVnoscreensaver 1` (SELinux-blocked), and disabling
  `org.droidtv.tv.inactivityservice` (it isn't the trigger).
- If you ever see **other** missing Philips overlays (e.g. a system popup that won't
  appear), remember the overlay-permission revoke in step 4 is a possible cause —
  re-grant it with the revert command to test.

## Quick reference

```bash
# Suppress Philips logo
adb -s 192.168.10.40:5555 shell appops set org.droidtv.tvsystemui SYSTEM_ALERT_WINDOW ignore
# Bring it back
adb -s 192.168.10.40:5555 shell appops set org.droidtv.tvsystemui SYSTEM_ALERT_WINDOW allow

# Our screensaver on / off
adb -s 192.168.10.40:5555 shell settings put secure screensaver_components com.fleece.screensaver/com.fleece.screensaver.BlackHoleDreamService
adb -s 192.168.10.40:5555 shell settings put secure screensaver_components com.google.android.backdrop/.Backdrop

# Idle start delay (ms)
adb -s 192.168.10.40:5555 shell settings put system screen_off_timeout 300000
```
