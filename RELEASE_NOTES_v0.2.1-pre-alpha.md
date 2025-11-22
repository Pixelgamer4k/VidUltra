# VidUltra v0.2.1-pre-alpha Release Notes

**Release Date:** November 23, 2025  
**Status:** Pre-Alpha  
**Build Type:** Debug

---

## 🎬 What's New

VidUltra v0.2.1-pre-alpha is the first public pre-alpha release featuring a professional "Supreme Cinema" UI and full manual camera controls for Android devices.

### Key Features

#### 📹 Professional Video Recording
- **4K HEVC Recording** at 30 FPS with 100 Mbps bitrate
- **Direct Gallery Save**: Videos automatically saved to `DCIM/VidUltra`
- **High-Quality Codec**: HEVC (H.265) for superior compression

#### 🎛️ Manual Camera Controls
- **ISO Control**: Adjustable range from 100 to 3200
  - Smart initialization at ISO 400 (typical auto level)
  - Smooth slider interface with real-time feedback
  - *Adjustable during recording*
  
- **Shutter Speed Control**: Professional discrete values
  - Range: 1/30s to 1/8000s
  - Arrow-based picker (◄ ►) for precise selection
  - 9 preset speeds: 1/30, 1/60, 1/125, 1/250, 1/500, 1/1000, 1/2000, 1/4000, 1/8000
  - *Adjustable during recording*
  
- **Focus Control**: Manual focus distance adjustment
  - Range: 0.0 to 10.0
  - Smooth slider interface

#### 🎨 Supreme Cinema UI
- **Edge-to-Edge Display**: Maximized preview area, optimized for punch-hole cameras
- **Frosted Glass Aesthetic**: Premium dark theme with gold accents
- **Live Histogram**: Real-time exposure monitoring (placeholder data)
- **Professional HUD**:
  - Bitrate indicator (100 Mbps)
  - Codec display (HEVC)
  - Bit depth (8-bit)
  - Log profile status
- **Recording Transparency**: Non-essential UI fades to 30% during recording

#### 🎯 User Experience
- **Bottom-Centered Control Dock**: Ultra-compact manual controls (ISO, S, F)
  - Always visible and accessible during recording
  - Perfectly centered for comfortable thumb access
- **Spring-Based Animations**: Smooth, bouncy transitions with professional polish
- **Custom Gallery Icon**: Vector-drawn icon (mountains + sun design)
- **Google Photos Integration**: Direct access to videos with fallback support

---

## 📦 Installation

### Requirements
- **Android Version**: 8.0 (API 26) or higher
- **Permissions Required**:
  - Camera access
  - Microphone access (for audio recording)
- **Recommended**: Device with Camera2 API support for full manual controls

### Install Steps
1. Download `VidUltra-v0.2.1-pre-alpha.apk` from the release assets
2. Enable "Install from Unknown Sources" in your device settings
3. Tap the APK file to install
4. Grant camera and microphone permissions when prompted
5. Launch VidUltra and start recording!

---

## 🔧 Technical Details

### Architecture
- **Camera API**: Camera2 API for low-level control
- **UI Framework**: Jetpack Compose with Material3
- **Pattern**: MVVM (Model-View-ViewModel)
- **Language**: Kotlin

### Video Specifications
| Specification | Value |
|--------------|-------|
| Resolution | 4K (3840×2160) |
| Frame Rate | 30 FPS |
| Codec | HEVC (H.265) |
| Bitrate | 100 Mbps |
| Bit Depth | 8-bit |
| Audio | AAC |

### UI Metrics
| Element | Size |
|---------|------|
| Control Dock | 148dp × 48dp |
| Control Buttons | 36dp diameter |
| Animation Duration | 300ms (fade) |
| Recording Transparency | 30% alpha |

---

## ⚠️ Known Limitations (Pre-Alpha)

1. **Histogram**: Currently displays placeholder data (not live camera values)
2. **Settings Display**: Shows static values (not dynamic from camera capabilities)
3. **Format Options**: Limited to 4K 30 FPS (no resolution/framerate selection yet)
4. **Focus Peaking**: Not yet implemented
5. **Advanced Features**: Zebras, waveform, LUTs not available in this release

---

## 🐛 Known Issues

### Confirmed Issues
- **None** - All development issues have been resolved in this build

### Untested Areas ⚠️

**This pre-alpha has not been tested on physical hardware.** The following areas may have undiscovered issues:

#### Camera Compatibility
- **Camera2 API Support**: Not all devices fully support Camera2 API manual controls
  - Some budget/older devices may have limited manual control capabilities
  - ISO and shutter speed ranges may vary by device
- **HEVC Encoding**: Not all devices support HEVC (H.265) codec
  - App may crash or fail to record on devices without HEVC support
  - Fallback codec handling not yet implemented

#### Platform Compatibility
- **Android Version Testing**: Only tested in emulator
  - MediaStore behavior may differ across Android 8-14
  - Scoped storage handling (Android 10+) untested on real devices
- **Device Variations**: UI layout not tested on:
  - Different screen sizes and aspect ratios
  - Various punch-hole/notch configurations
  - Tablets or foldable devices

#### Performance
- **4K Recording Performance**: Real-world performance unknown
  - Frame drops during recording possible on lower-end devices
  - Overheating during extended recording untested
  - Battery consumption during recording not measured
- **Manual Control Latency**: UI responsiveness during recording untested
  - ISO/shutter changes may have delay on some devices

#### Functionality
- **Gallery Integration**: MediaStore saving untested
  - Videos may not appear in gallery immediately on some devices
  - Google Photos integration not verified on real hardware
- **Permission Handling**: Camera/audio permission flow not tested on various Android versions
- **Edge Cases**: No testing for:
  - Low storage scenarios
  - Camera in use by another app
  - App backgrounding during recording

### Reporting Issues

If you encounter any bugs or issues while testing:
1. Check device compatibility (Camera2 API support, HEVC encoding)
2. Note your device model, Android version, and exact steps to reproduce
3. Report on GitHub Issues with logcat output if available

---

## 🚀 Coming Soon

The following features are planned for upcoming releases:

### v0.3.0 (Next Release)
- Real-time histogram data
- Focus peaking for manual focus assistance
- Dynamic settings display (actual camera capabilities)

### Future Roadmap
- Zebra stripes (overexposure indicator)
- Waveform monitor
- LUT support for color grading
- Multiple resolution options
- Frame rate selection (24, 60, 120 fps)
- RAW video recording (device-dependent)

---

## 📝 Release Notes

### Added
- Initial pre-alpha release
- 4K HEVC video recording at 30 FPS
- Manual ISO control (100-3200)
- Manual shutter speed control (1/30s - 1/8000s)
- Manual focus control
- Supreme Cinema UI with frosted glass design
- Bottom-centered control dock
- Custom gallery icon
- Google Photos integration
- Recording transparency animation
- Edge-to-edge display optimization
- Punch-hole camera support
- MediaStore gallery integration

### Changed
- N/A (initial release)

### Fixed
- N/A (initial release)

---

## 💬 Feedback

This is a **pre-alpha** release intended for early testing and feedback. Please report any issues, bugs, or feature requests on the GitHub Issues page.

---

## 🙏 Acknowledgments

VidUltra's Camera2 API architecture is inspired by the excellent work done on [FreeDcam](https://github.com/KillerInk/FreeDcam).

---

**VidUltra v0.2.1-pre-alpha** - Supreme Cinema in Your Pocket 🎥

*Built with ❤️ using Kotlin and Jetpack Compose*
