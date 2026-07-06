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
| adb address | `192.168.10.40:5555` |
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

### 4b. Scenes (random rotation)

The dream picks a **random scene** each time it activates (Android allows only one
registered screensaver, so rotation happens inside the app). Scene indices:
`0` black hole, `1` flow field, `2` wheat field, `3` murmuration, `4` vortex ocean,
`5` galaxy, `6` ink in water, `7` embers. All are in the random pool except wheat
field (`2`), which can only be shown by pinning it via the override below. (Starry
waves, aurora, and grass field were removed; note the indices shifted, so any
previously pinned `fleece_scene` value may now point at a different scene.)
To pin one (testing or preference):

```bash
adb -s 192.168.10.40:5555 shell settings put system fleece_scene 3   # always murmuration
adb -s 192.168.10.40:5555 shell settings delete system fleece_scene  # back to random
```

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
