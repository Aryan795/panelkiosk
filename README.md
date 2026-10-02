# PanelKiosk

Turn any Android 6.0+ phone or tablet into a wall panel for your dashboard: Home Assistant,
Dashy, Homepage, Grafana, or anything else with a URL. PanelKiosk shows that page full-screen and
runs the panel around it. It wakes the screen when someone walks up (camera motion), switches it
off when the room is empty, starts on boot, and can lock the device to itself. It covers what a
wall panel needs from Fully Kiosk, including a `window.fully` JS bridge and a Fully-style REST
API, with no license to buy.

## Install

```bash
# the Android Gradle plugin needs JAVA_HOME on JDK 17 or 21 (newer ones fail in jlink), e.g.
# Homebrew's keg-only openjdk@21 on Apple Silicon:
#   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# optional, for silent pinning + remote reboot (remove all accounts first):
adb shell dpm set-device-owner dev.aryan.panelkiosk/.AdminReceiver
```

Or install the APK from [Releases](https://github.com/Aryan795/panelkiosk/releases). First run
opens the settings: enter your dashboard's address. After that, swipe in from the bottom-right
corner (about 1.3 cm, up or left) to reopen them, or tap the top-left corner five times quickly.
For a Home Assistant dashboard use the IP address, e.g. `http://192.168.1.10:8123/lovelace/0`;
many tablets can't resolve `homeassistant.local`.

## What it does

- **Any screen, either way up.** Rotation follows the device or locks to landscape/portrait, and
  notches and punch-holes are kept clear. Folding or split-screen doesn't reload the page.
- **Starting on boot.** Reliable when PanelKiosk is the Home app or the device owner. On Android
  10–14, allowing it to display over other apps also works; Android 15 needs one of the first two.
  Settings shows which applies, with a button for each. Xiaomi, Huawei, Oppo and Vivo also need
  their own "Autostart" switch.
- **Camera optional.** It uses the front camera if there is one, else the back one, else a USB
  webcam. With none, camera wake simply switches off; a Home Assistant automation can still wake
  the panel through the REST API below.
- **Wakes from a truly dark screen.** Camera motion wake runs as a foreground service (you'll see
  a "watching for motion" notification), so it keeps watching with the display off. Turn on
  **True screen off** and the display really switches off when the room is empty, then comes back
  on when someone walks in. Set the lock screen to **None** (or Swipe): no app can get past a PIN,
  though the page still shows over the lock screen. With device admin the display goes off at
  once; without it, Android's own screen timeout switches it off, so set that short. Android 14+
  can revoke "Turn screen on" special access, and Settings tells you if it has.
- **Tune the camera on the spot.** Sensitivity is a 1–15 slider (1 = only big movement, 15 = the
  slightest change; 3, 5 and 8 keep the old low, medium and high thresholds). Under it a live bar
  shows how much of the picture is changing against the red wake line, so you can wave at the panel
  while you set it. The slider applies at once; Cancel puts the saved level back. Or press
  **Calibrate**: you get 5 s to step out of view, then for 8 s it measures how much the still room
  changes on its own (sensor noise, a flickering light) at every level at once, and moves the
  slider to the most sensitive level that stays under half the wake line. If the bar sits near the
  line with nobody moving, the panel will wake on its own.
- **Sleeps by itself on any page.** Set **Sleep after N minutes** and PanelKiosk sleeps after that
  long without camera motion or a touch, whatever the page. It stands down only for a page that
  switches the screen itself through `window.fully` (most dashboards don't), so the two never fight.
  A wake by the power button or double-tap-to-wake restarts the countdown too, and the countdown
  pauses while the settings (or an Android screen opened from them) are in front.
- **Keeps itself up.** It retries until the page is reachable (Wi-Fi is often late after a boot),
  and rebuilds the WebView if a low-memory device kills its renderer.
- **Fully-style REST API on `:2323`**: `/?cmd=screenOn`, `screenOff`, `rebootDevice` (device owner,
  Android 7+) and `deviceInfo`: `appVersionName`, `screenOn` (whether the display really shows
  something: it follows the power button too, and is false under the soft-sleep layer),
  `isPlugged`, and on devices with a battery `batteryLevel` (%) and `batteryTemperature` (°C, the
  battery's). Add `&password=` if one is set. It covers screen, reboot and status, not all of
  Fully: Home Assistant's Fully Kiosk integration won't set it up (it needs `deviceID`,
  `deviceName`, `Mac` and the settings commands). A `rest:` sensor reading `deviceInfo` works.
- Needs a current **Android System WebView** (104+); on older ones the page says so.

## Security

- The `:2323` API has no password until you set one in Settings, and without one anything on your
  network can switch the screen or reboot the device. Set one if your LAN isn't fully trusted.
