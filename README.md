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
- Everything runs as accessibility overlays, so no "draw over other apps" permission is required. Optional: allow it in the app if Lens doesn't open when you tap the handle with a saved area (it exempts the app from Android's background-launch limit). A saved-area search shows nothing on screen: no toasts, no hints.
- Edge handles: the top handle is the default one - tap it once to search. Press, hold and drag any handle to move the whole cluster anywhere along either edge.
- App shortcuts: in the app, "Edge handles" -> **Add app shortcut** (up to 8). Pick an app, choose its handle colour
  (pre-filled from the app icon, e.g. black for ChatGPT, green for WhatsApp) and it gets its own handle under the
  default one. One tap opens that app in a floating window. Deleting the shortcut (or uninstalling the app) removes
  its handle automatically; UNDO is offered. Tap a colour dot to recolour a handle at any time.
  Handle height (24-80 dp, same for every handle) and opacity (0-100%, whole cluster) are sliders in the same card.
  Floating app windows use Android freeform windows (Developer options -> Enable freeform windows, and
  "Force activities to be resizable" if an app refuses). Where that is off, the app opens normally.
- Saved search area: pick a spot once and every tap on the edge handle searches exactly that spot (no circling).
  Save it from the lasso screen ("Save search area" button, shown when you open the lasso from the bubble or
  "Try it now"), or from the app ("Saved search area" -> Set area). Drag to draw, drag inside to move, drag the
  corners to resize. Remove it from the same places ("Remove area") and the handle goes back to opening the lasso.
  The bubble always opens the lasso. A portrait area isn't applied in landscape (the lasso opens instead).
- Lens app floating window needs freeform windows (Developer options). Otherwise use
  "Built-in floating Lens window" in the app.
- The built-in window uses Google Lens' web upload endpoint, which is unofficial and could change.

## Clipboard (PinClip window, built in)
The PinClip floating clipboard now lives inside Circle Search. In the app, switch on **Clipboard**: a clipboard handle
appears in the edge-handle cluster, right under the Circle Search handle (it turns on Edge handles for you if they were off).
Tap it and the PinClip window opens exactly as in the PinClip app: cards, groups, search, pin, multi-select, paste-all,
image cards, editor, drag to move, resize grip, full-screen toggle. Tap a card and it is pasted into the text field you
are typing in; tap the handle again (or the minimise button) to close the window.
- It is an accessibility overlay hosted by the Circle Search service, so no extra permission is needed. Pasting uses the same
  accessibility service (it needs the field to have input focus; otherwise the text is just copied).
- The handle colour is the "Clipboard" row under Edge handles; window opacity, auto-save of copies/screenshots and
  Backup / Restore (also reads PinClip backup .zip files) are in the Clipboard card.
- Circle Search hides the window for a moment while it takes its screenshot.
- Code lives in `clip/` (`ClipboardWindow.kt` is the PinClip window, `Store.kt` its data).

## Background running & permissions

Declared in `AndroidManifest.xml`: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, WAKE_LOCK, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
and (only for clipboard auto-save of screenshots, asked when you turn it on) READ_MEDIA_IMAGES.
The accessibility service runs as a foreground service (silent notification) while the
"Circle Search on" switch in the main screen is on. Use "Allow unrestricted battery" there so
Android doesn't kill it. On some phones (Xiaomi, Oppo, Vivo, Samsung) also enable Autostart /
"Don't optimize" / lock the app in Recents.

## Prompt search
In the main app, "Search with prompt" holds a text prompt sent together with the cropped picture on every search. Switch it off and only the picture is searched (the text stays saved). Lens app mode: PromptFiller.kt types it into the "Ask about this image" box via accessibility (turn the accessibility service off and on once after updating). Built-in window: added as q= on the results URL. Code anchors: search for `PROMPT_SEARCH`.
