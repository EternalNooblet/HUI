# Vision + d3 Method — Android UI Element Localization

## Purpose
Locate exact pixel coordinates of Android UI elements on the phone screen for reliable `adb input tap` without hand-holding or guessing. Used when uiautomator bounds are missing, stale, or ambiguous.

## Prerequisites
- Phone reachable via ADB: `adb -s 10.0.0.167:5555`
- Decision API key in `${VLLM_API_KEY}` (sk-vsr-*)
- Screen resolution known: `adb shell wm size` (LG K30 = 720x1280)

## Step-by-step

### 1. Capture screen
```bash
adb -s 10.0.0.167:5555 shell screencap -p /sdcard/screen.png
adb -s 10.0.0.167:5555 pull /sdcard/screen.png /home/papa/.hermes/cache/scratch/screen.png
```

### 2. Describe screen with vision
Call `vision_analyze` on the screenshot. Ask for:
- Exact text in input fields
- Whether keyboard is showing
- Whether any chat bubbles appear in main area
- Button positions and colors
- Whether popups/dialogs/overlays are present
- Time/battery/status bar state

### 3. Get precise coordinates

**Option A: uiautomator (fast, exact when it works)**
```bash
adb -s 10.0.0.167:5555 shell uiautomator dump /sdcard/view.xml
adb -s 10.0.0.167:5555 pull /sdcard/view.xml /home/papa/.hermes/cache/scratch/view.xml
grep -o 'resource-id="com.southpawin.companion:id/promptInputSendBtn"[^>]*bounds="[^"]*"' view.xml
```
Center = midpoint of bounds `[x1,y1][x2,y2]`.

**Option B: d3-ultra box query (when uiautomator fails or coords uncertain)**

Build JSON request to the Decision API:

```json
{
  "model": "vllm-sr/d3-ultra",
  "state": "Android chat app screen. Find the target element.",
  "questions": {
    "target": {
      "type": "box",
      "instructions": "the blue paper-plane send arrow button at the right end of the bottom input row"
    }
  },
  "images": ["data:image/png;base64,<BASE64>"],
  "options": {"return_meta": true}
}
```

Send:
```bash
curl -s -X POST https://decision.vllm-sr.ai/v1/systemone \
  -H "Authorization: Bearer $VLLM_API_KEY" \
  -H "Content-Type: application/json" \
  --data-binary @box_req.json
```

Key rules for box phrasing (from d3-ultra docs):
- Instructions are a **noun phrase**, not an imperative ("the blue send arrow", not "draw a box around X")
- Spell out extent if needed ("the whole word X, all four letters")
- If several matches exist, specify which one
- Box always returns a box even if target absent — pair with a `noul` question to test presence

Response gives normalized box `[left, top, right, bottom]` as fractions 0–1 + confidence.
Convert to pixels: `x = fraction * width`, `y = fraction * height`. Center = midpoint.

Example: screen 720x1280, box [0.3386, 0.8173, 0.4009, 0.8650] → center ≈ (266, 1077), confidence 0.719.

### 4. Tap
```bash
adb -s 10.0.0.167:5555 shell input tap <x> <y>
```

### 5. Verify
- Check screenshot again with `vision_analyze` — did text clear from input? Did a bubble appear?
- Check server log: `tail -5 /tmp/wss_server.log` — did `chat` event arrive? Did mock reply send?
- Check phone logcat if needed: `adb logcat -d -s "HUI_DEBUG:D"`

## When to use each option
- Use uiautomator first — it's free and gives exact element bounds
- Use d3-ultra box when uiautomator returns nothing, bounds are stale after app restart, or the element isn't in the hierarchy

## Known result (verified 2026-11-03)
- Send button center: (266, 1077) on 720x1280 screen
- Tap at (266, 1077) → server logged `chat from android-80c7ff2b51dc9a7b: debugverify` + mock reply sent
- Input field cleared after send (placeholder "Ask prompt-kit..." visible)
- No assistant bubble appeared on screen after 5s — bubble rendering is a separate open issue
