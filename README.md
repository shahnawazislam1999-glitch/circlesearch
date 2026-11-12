# Circle Search (personal Android app)

Floating bubble -> circle anything on screen -> search with Google Lens.

## Build the APK on GitHub
1. Create a repo and push this folder (the `.github` folder must be included).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Download the `CircleSearch-debug-apk` artifact, unzip, install `app-debug.apk`.

No Gradle wrapper is needed: the workflow installs Gradle 8.9, JDK 17 and the Android SDK.

## First run
1. Open the app, tap **Open accessibility settings**, enable **Circle Search**.
   If the switch is greyed out: App info -> three-dot menu -> **Allow restricted settings**.
2. The bubble appears. Tap it, circle (or tap) something, release. Lens opens with the crop.

## Notes
- Everything runs as accessibility overlays, so no "draw over other apps" permission is needed.
- Edge handle: tap the faint handle once to search; press, hold and drag to move it anywhere along either edge.
- Lens app floating window needs freeform windows (Developer options). Otherwise use
  "Built-in floating Lens window" in the app.
- The built-in window uses Google Lens' web upload endpoint, which is unofficial and could change.

## Background running & permissions

Declared in `AndroidManifest.xml`: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, WAKE_LOCK, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
The accessibility service runs as a foreground service (silent notification) while the
"Circle Search on" switch in the main screen is on. Use "Allow unrestricted battery" there so
Android doesn't kill it. On some phones (Xiaomi, Oppo, Vivo, Samsung) also enable Autostart /
"Don't optimize" / lock the app in Recents.
