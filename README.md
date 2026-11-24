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

## ChatGPT search handle
In the app, switch on **ChatGPT search**: a new thin edge handle appears right under the Circle Search handle (it turns on Edge handles for you).
Tap it and the screenshot is shared to the real ChatGPT app together with the prompt you typed in the card.
- **Prompt**: text box in the card (switch "Send the prompt" off to send only the picture). It is also copied to the clipboard as a backup.
- **Auto send**: after ChatGPT opens, the accessibility service makes sure the prompt is in the message box and presses send once the image has uploaded. Switch "Press send automatically" off to review it yourself first.
- **Set area**: the card has its own saved area (separate from the Circle Search one). With an area saved, one tap sends exactly that part of the screen; without one the lasso opens. Remove it to go back to the lasso.
- Needs the ChatGPT app (`com.openai.chatgpt`) installed. ChatGPT can change its layout, so if auto send ever misses, the image and prompt are still in the app and a toast tells you to tap send. After updating, turn the accessibility service off and on once. Code anchors: search for `GPT_SEARCH`.

## Handle look
Handles are now a 2 dp hairline hugging the screen edge, with a small gap between handles and a faint shadow so light colours stay visible on white screens. Touch area is still 24 dp wide. Default opacity is 85%.

## Clipboard v5: colours, ⋮ menu, full images, Sticky note (Canva) view
- Every card has its own pastel colour (change it in the card editor). Old cards were coloured automatically.
- Header is now just: back, pin, title, **+** and **⋮**. Search, Reading mode, Select multiple, Add image, Full screen and
  **Sticky note view** are inside the ⋮ menu.
- Image cards show the whole picture (never cropped, full quality). The expand icon on an image card opens a zoom viewer
  (pinch, drag, double-tap, Fit / 100% actual pixels).
- **Sticky note view** (⋮ -> Sticky note view): the window becomes a Canva-style board of coloured sticky notes.
  Group chips on top filter the notes. Hand tool: drag a note to move it, drag its blue corner dot to resize, tap it for
  Paste / Copy / Edit / Color / Pin / Delete (double-tap pastes). Drag empty space to pan, pinch with two fingers to zoom.
  Pencil tool: smart pen or finger writes with the floating-pen pencil (same smoothing); pick colour + stroke size, eraser, undo.
  The fit icon shows everything. The window itself still moves/resizes freely.
- Everything is remembered: sticky mode on/off, board position + zoom, tool/colour/size, last group filter, note positions,
  drawing (board.json). Backup (zip) now contains the notes layout, colours and the drawing; restore brings them back.

## Clipboard v6: clean header, long-press actions, pan lock, Reading mode, Favourites, real full screen
- **Clean list**: cards show only their text. Tap = paste. **Press and hold** a card and an action bar opens right under it:
  Pin / Favourite / Edit / Copy / Expand / Select / Delete. Tap the card again (or anywhere) to close it.
- **Everything that sat above the cards is now in the ⋮ menu**: the list switcher (All, Favourites, Pinned, Top used, your
  groups, + Group), Paste all + Join, and in sticky note view the board tools (move / pan / pen / eraser, undo, colours, sizes,
  fit all). The header title shows which list you are in with a card count; tap it to open the menu.
- **Favourites**: press and hold a card or sticky note -> Favourite. Open them from ⋮ -> Show -> Favourites. Favourite notes
  wear a small gold star on the board; list cards show a star next to the pin.
- **Pan only (sticky note view)**: the hand button in the header (or ⋮ -> Board tools -> Pan only) locks the cards. One finger
  slides the canvas even when it starts on a note, two fingers pinch-zoom, tap still selects. Tap the button again to move
  cards again. While the pen is active a small Undo / Done pill appears at the bottom.
- **Sticky note long press**: press and hold a note and its action bar floats at the bottom of the board
  (Paste / Copy / Edit / Colour / Pin / Favourite / Delete). Tap empty canvas to close it.
- **Reading mode** (⋮ -> View -> Reading mode) now works on the board too: every note grows to show all of its text and pushes
  the notes below it down so nothing overlaps. Positions are never changed - switch it off and the board is exactly as you left
  it. Moving cards and drawing are paused while it is on.
- **Full screen** now really is full screen: the window starts at the very top, covers the status bar / notification icons and
  only keeps clear of the camera cut-out and navigation bar.
