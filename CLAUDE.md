# CLAUDE.md

Guidance for AI assistants (and humans) working in this repository.

## What this app is

**CUCo Scanner** is an Android app (Kotlin) that helps unlock an Inforlandia
**CUCo**-protected computer. The CUCo lock screen shows three hex fields; the app
photographs that screen, reads the fields with on-device OCR, and opens the
official CUCo unlock site (`cuco.inforlandia.pt/ucode/`) in a WebView with the
fields pre-filled so the user can obtain an unblock code.

The three fields:

| Field | Canonical name | Typical length | Notes |
|-------|----------------|----------------|-------|
| Machine Serial Number | `serial` | 32 hex chars | Sent to the site via the URL `l=` query param, **not** the form. |
| Certified Time | `ctime` / `certified` | ~8 hex chars | Filled into the web form via JS. |
| Usage Counter | `usage` | ~1–8 hex chars | Filled via JS; leading zeros trimmed (`00000001` → `1`). |

## High-level data flow

```
MainActivity ──(take photo)──> CameraActivity ─┐
            └─(pick gallery)──────────────────┐│
                                              ▼▼
                                         ScanActivity
                              (CucoImagePreprocessor → ML Kit OCR
                                       → CucoOcrParser)
                                              │ 3 fields
                                              ▼
                                       WebViewActivity
                         (buildCucoUrl + FillFormJs autofill + history)
                                              │
                                              ▼
                                  cuco.inforlandia.pt/ucode/
```

`HistoryActivity` lists past scans (stored by `HistoryRepository`) and re-opens
`WebViewActivity` for any of them. `SettingsActivity` edits the URL pieces stored
by `SettingsRepository`.

## Module / class map

All code is under `app/src/main/java/pt/cuco/scanner/`.

- **MainActivity** – entry screen: take photo, pick from gallery, open History,
  open Settings.
- **CameraActivity** + `CucoCameraGuideView` – CameraX capture with a guide
  overlay; returns an image URI.
- **ScanActivity** – runs OCR over many preprocessed image variants, scores and
  votes (`charLevelVote` consensus), then opens `WebViewActivity` with the best
  fields.
- **CucoImagePreprocessor** – decodes/rotates the photo, detects the blue CUCo
  screen, perspective-corrects, and produces ~16 OCR variants (contrast,
  threshold, sharpen, blue-suppression, field-band and value-column crops).
- **CucoOcrParser** – pure-Kotlin (JVM-testable) parser that turns OCR text/lines
  into the 3 hex fields. Handles mangled labels, `O→0`/`I→1` typos, numbered
  prefixes (`1.`/`2.`/`3.`), spatially separated columns, value-only rows, and
  **serials that wrapped across two lines** (see `repairSerial`).
- **WebViewActivity** – builds the CUCo URL (`buildCucoUrl`), loads it, injects
  `FillFormJs`, listens for field edits / submission success, and records history.
- **FillFormJs** – the injected JavaScript: finds the form inputs (by selector and
  by keyword, across shadow DOM / iframes), fills them, attaches edit listeners
  (`window.CUCO.onFieldChanged`), and detects the success message
  (`window.CUCO.onSubmissionSuccess`).
- **HistoryRepository** / `HistoryEntry` – JSON-in-SharedPreferences store of past
  scans (max 100); `addEntry`, `updateFields`, `updateStatus`, `deleteEntry`.
- **HistoryActivity** – list UI; tap to re-open a code (reusing its history row via
  `EXTRA_HISTORY_ID`), long-press to delete.
- **SettingsRepository** / **SettingsActivity** – user-editable `client` / `lang` /
  `baseUrl`, plus `parseCucoUrl` to import a pasted full URL.

## The CUCo URL contract (important, changes often)

Current shape:

```
https://cuco.inforlandia.pt/ucode/?client=jpik_tipo1&lang=pt&l=<machineId>
```

- `l` is the **machine serial number** (the machine id). The site reads the serial
  from the URL, *not* from a form field — so correcting the serial requires
  rebuilding + reloading the URL (`WebViewActivity` does this when the serial is
  edited).
- `client` and `lang` have changed before. They are **defaults in
  `SettingsRepository`** (`DEFAULT_CLIENT = "jpik_tipo1"`, `DEFAULT_LANG = "pt"`)
  and are **editable in-app** via `SettingsActivity`. If the URL changes again,
  prefer updating the default constant and/or letting the user paste the new URL,
  over scattering URL logic.

## Build & test

```bash
# Unit tests (JVM, no device) — covers CucoOcrParser
./gradlew :app:testDebugUnitTest

# Debug APK
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

- Kotlin, `minSdk 24`, `targetSdk/compileSdk 34`, Java 17, view binding enabled.
- Dependencies: ML Kit text recognition, CameraX, Material 3, AppCompat,
  ConstraintLayout, ExifInterface. Building needs access to
  `dl.google.com` / `maven.google.com`.

## Conventions & gotchas

- **`CucoOcrParser` must stay JVM-pure** (no Android imports) so its unit tests
  run without a device. Add a test in `CucoOcrParserTest` for any parsing change.
- OCR robustness comes from *many variants + voting*, not a single perfect read.
  When fixing OCR issues, prefer adjusting `CucoImagePreprocessor` crops/filters
  and `CucoOcrParser` heuristics, and add a regression test.
- The serial is 32 chars and **wraps on the LCD**; never assume a single ≥16-char
  hex chunk is the whole serial — `repairSerial` rejoins the halves.
- Form autofill is resilient to site markup changes via keyword matching in
  `FillFormJs`; if a field stops filling, adjust its selectors/keywords there.
- History/edit state: each WebView session maps to one `HistoryEntry`; edits
  update that entry (`updateFields`) rather than creating duplicates.

See `docs/FEATURES.md` for the user-facing feature list and expected behavior.
