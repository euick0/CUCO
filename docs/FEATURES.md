# CUCo Scanner — Features & Expected Behavior

This document lists what the app does today and how each feature is expected to
behave. It is written to be useful both to users and to AI assistants reasoning
about the code. For architecture and class-level detail, see `../CLAUDE.md`.

## Overview

The app reads the three hex fields from a CUCo lock screen and opens the official
CUCo unlock page with them pre-filled:

1. **Machine Serial Number** (`serial`) — 32 hex chars
2. **Certified Time** (`ctime`) — ~8 hex chars
3. **Usage Counter** (`usage`) — ~1–8 hex chars (leading zeros trimmed)

---

## Implemented features

### 1. Capture
- **Take photo** (`CameraActivity`): CameraX preview with a guide overlay
  (`CucoCameraGuideView`) that frames the blue CUCo screen and marks the three
  field rows. Captured in maximize-quality mode.
- **Pick from gallery** (`MainActivity` → `PickVisualMedia`): use an existing
  photo instead of the camera.
- Camera permission is requested at runtime before the camera opens.

### 2. OCR pipeline (`ScanActivity` + `CucoImagePreprocessor` + `CucoOcrParser`)
- On-device **ML Kit** text recognition (no internet needed for OCR).
- The image is decoded with EXIF rotation applied, the **blue CUCo screen is
  detected**, perspective-corrected if skewed, and turned into ~16 variants
  (contrast stretch, Otsu threshold, unsharp mask, blue-channel suppression, a
  full-width **field-band** crop and a **value-column** crop).
- Each variant is OCR'd; results are parsed, **scored**, and combined with a
  character-level **consensus vote** across variants. The best-scoring result is
  used.
- **Quality feedback**: if parsing fails, the toast explains the likely cause —
  screen not found / move closer, glare, or blur.

### 3. Field parsing (`CucoOcrParser`) — robustness rules
- Tolerates mangled OCR labels and common substitutions (`O→0`, `I/l/L→1`,
  lowercase hex → uppercase).
- Uses the numbered prefixes `1.` / `2.` / `3.` as positional anchors when labels
  are unreadable.
- Handles values in a separate column (spatial row matching) and value-only rows
  from tight crops.
- **Reassembles a serial that wrapped across two lines.** The 32-char serial often
  wraps to two ~16-char rows on the LCD; the parser rejoins the halves instead of
  keeping only the first half (`repairSerial` / `joinWrappedSerial`). This is the
  fix for serials being "cut in the middle".
- Rejects implausible results (duplicate field values, out-of-range lengths, the
  "Enter Unblocking Code" noise line).

### 4. Web autofill & submission (`WebViewActivity` + `FillFormJs`)
- Builds the URL `…/ucode/?client=…&lang=…&l=<serial>` and loads it. The serial is
  passed via the `l=` query param (the site reads it from the URL).
- Injects JavaScript that locates the Certified Time and Usage Counter inputs (by
  id/name selectors and by keyword across labels, placeholders, shadow DOM and
  iframes) and fills them, firing `input`/`change` events.
- Detects the success message and marks the history entry as **submitted**.

### 5. Remembering edits / corrections (`WebViewActivity`)
- Edits the user makes in the web form are captured and **persisted** to the
  current history entry, and **re-applied automatically after a page reload**
  (e.g. when the site reloads showing that a field was wrong).
- Because the serial lives in the URL, editing the serial in the form triggers a
  **reload with the corrected `l=`**, so the correction actually takes effect on
  resubmit.
- Working state survives screen rotation / activity recreation
  (`onSaveInstanceState`).

### 6. History (`HistoryActivity` + `HistoryRepository`)
- Every scan/session is recorded (serial, ctime, usage, timestamp, status), up to
  100 entries, in SharedPreferences.
- Open History from the **main screen** or the **website screen**. Tapping an entry
  re-opens the unlock page pre-filled with that code; it **reuses the same history
  row** (no duplicates) via `EXTRA_HISTORY_ID`.
- Long-press an entry to delete it. Status is shown as *Pendente* / *Enviado*.

### 7. In-app URL settings (`SettingsActivity` + `SettingsRepository`)
- Edit the `client`, `lang`, and base URL used to build the CUCo link, so the app
  keeps working when Inforlandia changes the address.
- **Import from a pasted full URL**: parses `client`, `lang`, and (if present) the
  `l=` machine id; offers to open that code directly.
- Reset to defaults (`client=jpik_tipo1`, `lang=pt`,
  `https://cuco.inforlandia.pt/ucode/`).

---

## Expected behavior summary

- A clear, well-framed photo of the blue CUCo screen should yield all three fields
  and open the unlock page with them filled.
- A wrong field can be corrected in the page; the correction is remembered across
  reloads, and serial corrections reload the page with the new serial.
- Previous codes can be reopened from history (main screen or website screen) and
  load into the page.
- If the CUCo URL format changes, it can be fixed from Settings without a new app
  build.

## Known limitations / future ideas

- OCR accuracy depends on photo quality (lighting, glare, focus, framing).
- The unlock-code result itself is produced by the CUCo website, not stored by the
  app.
- Form autofill relies on the site's markup; large redesigns may require updating
  `FillFormJs` selectors/keywords.
- No automated UI tests yet; `CucoOcrParser` has JVM unit tests
  (`CucoOcrParserTest`).
