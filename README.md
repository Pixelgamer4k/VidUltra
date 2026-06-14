# VidUltra Pro

A professional, full-screen Android video recording app inspired by mcpro24fps — built for cinematographers who want full manual control, log gamma options, HDR/10-bit capture, and a distraction-free top-to-bottom viewfinder.

## Highlights

- **Full-screen preview** with no display cutout / notch cropping (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`).
- **Manual camera controls**: ISO, shutter speed / shutter angle, focus distance, white balance (Kelvin), exposure compensation.
- **Log / gamma profiles**: Rec.709, S-Log, S-Log2, S-Log3, V-Log, LogC, Cineon, ACEScc, PQ, HLG, Flat.
- **HDR & 10-bit** toggles (device-dependent; falls back gracefully on unsupported hardware).
- **High bitrate selection** up to 400 Mbps.
- **All lenses**: rear wide, front, external (Camera2 logical / physical cameras).
- **Noise reduction OFF** and **sharpening OFF** for a clean, cinematic image.
- **Optical / electronic stabilization** toggle.
- **Focus bracketing** for rack-focus / macro stacking effects.
- **Live RGB + luma histogram** overlay.
- **Frame-rate selection**, resolution selection, audio toggle, HEVC/H.264 codec.

## Tech Stack

- Kotlin
- Camera2 API (`android.hardware.camera2`)
- MediaRecorder
- ViewBinding
- Coroutines
- GitHub Actions CI (cloud builds for low-end machines)

## Build

Because this project can be heavy to build on a low-RAM machine, the recommended workflow is GitHub Actions:

```bash
# 1. Push the repo to GitHub
git init
git add .
git commit -m "Initial VidUltra Pro commit"
gh repo create VidUltra --public --source=. --remote=origin --push

# 2. Trigger a workflow run (or push a new commit)
git push origin main

# 3. Download the built APK
gh run list --repo <user>/VidUltra
gh run download <run-id> --repo <user>/VidUltra
```

Local builds are also possible if your machine has enough RAM:

```bash
./gradlew assembleDebug
```

## Permissions

- `CAMERA`
- `RECORD_AUDIO`
- `WRITE_EXTERNAL_STORAGE` (API ≤ 28)

## Project Structure

```
app/src/main/java/com/vidultra/pro/
├── MainActivity.kt
├── VidUltraApp.kt
├── camera/
│   ├── CameraController.kt      # Camera2 session, manual controls, recording
│   ├── CameraSettings.kt        # Immutable settings model
│   └── VideoRecorder.kt         # MediaRecorder wrapper + MediaStore
├── settings/
│   └── SettingsManager.kt       # SharedPreferences persistence
├── ui/
│   ├── AutoFitTextureView.kt    # Aspect-ratio preserving preview
│   └── HistogramView.kt         # RGB/luma histogram
└── utils/
    ├── Constants.kt
    └── LogProfile.kt
```

## Roadmap / Known Limitations

- HDR/10-bit and log profiles are ultimately limited by OEM Camera2 implementation. The app exposes the UI and best-effort CaptureRequest flags; actual support varies per device.
- True RAW/DNG video is not implemented yet.
- Waveform / vectorscope / false color can be added as future overlays.

## License

MIT
