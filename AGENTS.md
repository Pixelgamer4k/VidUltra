# VidUltra Pro — Agent Notes

## Build & CI

- **Do not build locally on low-end machines.** The project is configured for GitHub Actions via `.github/workflows/android-build.yml`.
- Use `gh repo create`, `git push`, and `gh run` commands to build and download APKs.
- If you must build locally, ensure at least 8 GB RAM and JDK 17: `./gradlew assembleDebug --no-daemon`.

## Architecture

- **Camera2 API only.** Do not replace the core with CameraX unless explicitly asked; manual controls and NR/edge/sharpening toggles rely on `CaptureRequest` keys.
- **State lives in `CameraSettings`** and is persisted through `SettingsManager` (`SharedPreferences`).
- **UI state is refreshed from `CameraController.getSettings()`** after every change.
- Recording session is recreated from preview session because `MediaRecorder` surface must be included at session creation time.

## Coding Conventions

- Kotlin, 4-space indentation.
- Use `androidx.appcompat:appcompat` for dialogs and `ViewBinding` for layouts.
- Keep camera logic in `camera/` package, UI widgets in `ui/` package.
- Prefer immutable `CameraSettings.copy()` over mutable fields.

## UI Requirements

- Full-screen, no action bar, no cutout cropping.
- All controls are semi-transparent overlays over the preview.
- Left panel: lens, log, HDR, 10-bit, bitrate, stabilization, histogram, focus bracket, NR, sharpening.
- Right panel: ISO, shutter, focus, WB, EV, FPS.
- Bottom bar: resolution, record button, settings.

## Permissions

- `CAMERA` and `RECORD_AUDIO` are mandatory.
- `WRITE_EXTERNAL_STORAGE` is only requested on API ≤ 28.

## Testing

- CI runs `lintDebug`, `assembleDebug`, and `assembleRelease`.
- Manual testing requires a physical Android device (Camera2 behavior varies on emulators).
