# VidUltra 🎬

A professional-grade Android camera application with manual controls, inspired by FreeDcam's Camera2 API architecture, featuring a stunning "Supreme Cinema" UI.

## Features ✨

### Camera Capabilities
- **4K HEVC Recording** at 30 FPS
- **Manual Controls**: ISO (100-3200), Shutter Speed (1/30s - 1/8000s), Focus
- **Real-time Adjustments**: Change ISO and shutter speed during recording
- **Gallery Integration**: Direct save to DCIM/VidUltra with MediaStore API
- **Edge-to-Edge Display**: Optimized for punch-hole cameras

### User Interface
- **Supreme Cinema Aesthetic**: Gold accents, frosted glass design
- **Live Histogram**: Real-time exposure visualization
- **Professional Settings Display**: Bitrate (100 Mbps), Codec (HEVC), Bit Depth (8-bit)
- **Compact Manual Dock**: Bottom-centered controls (ISO, S, F)
- **Arrow-Based Shutter Picker**: Discrete speed selection for precise control
- **Custom Gallery Icon**: Vector-drawn image icon
- **Recording Transparency**: Non-essential UI fades during recording

## Tech Stack 🛠️

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Camera API**: Camera2 API (low-level control)
- **Architecture**: MVVM pattern
- **Video Codec**: HEVC (H.265)
- **Build System**: Gradle with Kotlin DSL

## Project Structure 📁

```
app/src/main/java/com/pixelgamer4k/vidultra/
├── core/
│   └── Camera2Api.kt          # Low-level Camera2 wrapper
├── ui/
│   ├── CameraScreen.kt        # Main UI composable
│   └── CameraViewModel.kt     # State management
└── MainActivity.kt             # Entry point
```

## Building 🔨

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run the app
./gradlew run
```

## Architecture Decisions 🏗️

### Camera2Api Core
- Direct Camera2 API access for maximum control
- Inspired by FreeDcam's robust architecture
- Manual ISO, exposure time, and focus distance control
- MediaStore integration for public gallery access

### UI Design Philosophy
1. **Edge-to-Edge Immersion**: Full-screen preview with punch-hole optimization
2. **Manual Control Priority**: Bottom-centered dock for easy thumb access during recording
3. **Visual Hierarchy**: 
   - Left: Settings & Histogram (fade during recording)
   - Right: Shutter button + Format indicator
   - Bottom: Manual controls (always visible)
4. **Premium Aesthetics**: Frosted glass, gold accents, smooth animations

## Manual Controls 🎛️

### ISO Slider
- Range: 100 - 3200
- Starts at auto level (400)
- Horizontal slider with real-time value display

### Shutter Speed Picker
- Discrete values: 1/30s, 1/60s, 1/125s, 1/250s, 1/500s, 1/1000s, 1/2000s, 1/4000s, 1/8000s
- Arrow navigation (◄ ►)
- Large display (28sp)

### Focus Control
- Range: 0.0 - 10.0
- Horizontal slider
- Smooth focus pull

## Animations 🎨

- **Slider Popup**: Spring-based scale + fade + slide
  - Damping: Medium bouncy
  - Stiffness: Low
- **Recording Transparency**: 300ms fade to 30% for non-essential UI
- **Control Toggles**: Instant highlight with gold background

## Permissions 🔐

Required at runtime:
- `CAMERA`: Camera access
- `RECORD_AUDIO`: Audio recording

Manifest-only:
- `INTERNET`: Future network features

## Roadmap 🗺️

- [x] Core camera functionality
- [x] Manual controls (ISO, Shutter, Focus)
- [x] Supreme Cinema UI
- [x] Gallery integration
- [x] Recording transparency
- [x] Custom gallery icon
- [ ] Focus peaking
- [ ] Zebra stripes
- [ ] Waveform monitor
- [ ] LUT support

---

**VidUltra v0.2.1-pre-alpha** - Supreme Cinema in Your Pocket 🎥

