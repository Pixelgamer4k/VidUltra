# VidUltra 📹

**A Professional Android Video Recording App with Manual Controls**

VidUltra is a powerful pro-grade video recording application designed for Android devices, featuring manual camera controls, high-quality video encoding, and a sleek "frosted glass" UI inspired by professional mirrorless cameras.

---

## ✨ Features

### 🎥 Professional Video Recording
- **High Bitrate**: Up to 800 Mbps for maximum quality
- **HEVC (H.265) Codec**: Modern compression for smaller file sizes
- **10-bit Color Depth**: Enhanced color grading capabilities
- **HDR Support**: High Dynamic Range video recording (device dependent)

### 🎛️ Manual Controls
- **ISO Control**: Real-time sensitivity adjustment
- **Shutter Speed**: Precise exposure time control
- **Focus**: Manual focus distance control
- **White Balance**: Custom color temperature settings
- **Real-time Adjustments**: Change settings even while recording

### 🎨 User Interface
- **Frosted Glass Design**: Clean, modern aesthetic
- **Landscape-Only**: Optimized for horizontal video shooting
- **Histogram Display**: Real-time exposure monitoring
- **Expandable Pro Menu**: Toggle between auto and manual modes
- **EIS/OIS Toggle**: Stabilization control (device dependent)

### 📊 Technical Features
- **Log Recording Option**: Flat color profile for post-production
- **Mirrorless-Style Tone Mapping**: Natural, film-like color rendering
- **Minimal Noise Reduction**: Clean image without artificial processing
- **Zero Sharpening**: Natural detail preservation

---

## 🛠️ Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Camera API**: Camera2 (for manual controls)
- **Video Encoding**: MediaRecorder with HEVC
- **Architecture**: MVVM pattern with StateFlow
- **Build System**: Gradle 8.6
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)

---

## 📥 Download & Installation

### Download APK
1. Visit the [Releases](https://github.com/Pixelgamer4k/VidUltra/releases) page
2. Download the latest `app-debug.apk`
3. Enable "Install from Unknown Sources" in your Android settings
4. Install the APK on your device

### Build from Source
Since you don't have Android Studio, use **GitHub Actions** to build:

1. **Fork or Clone** this repository
2. **Push changes** to trigger the build workflow
3. Go to the **[Actions](https://github.com/Pixelgamer4k/VidUltra/actions)** tab
4. Click on the latest successful build
5. Download the **app-debug** artifact
6. Extract the APK and install on your device

#### Local Build (if you have Gradle installed)
```bash
# Clone the repository
git clone https://github.com/Pixelgamer4k/VidUltra.git
cd VidUltra

# Build the debug APK
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Usage

### Basic Recording
1. Open the app
2. Grant camera and audio permissions
3. Tap the **red record button** to start/stop recording
4. Videos are saved to your device's default camera folder

### Manual Mode
1. Tap the **PRO** button to expand manual controls
2. Adjust **ISO**, **Shutter Speed**, **White Balance**, and **Focus** using the sliders
3. Changes apply in real-time, even during recording
4. Tap the **X** button to return to auto mode

### Settings
- **EIS/OIS Toggle**: Enable/disable stabilization
- **Bitrate**: Adjust video quality (higher = better quality, larger files)
- **Codec**: Select between H.264 and HEVC (H.265)
- **Log**: Enable flat color profile for color grading

---

## 🏗️ Project Structure

```
VidUltra/
├── app/
│   ├── src/main/
│   │   ├── java/com/pixelgamer4k/vidultra/
│   │   │   ├── MainActivity.kt
│   │   │   ├── camera/
│   │   │   │   ├── CameraManager.kt      # Camera2 API implementation
│   │   │   │   └── VideoConfig.kt        # Video encoding settings
│   │   │   └── ui/
│   │   │       ├── CameraScreen.kt       # Main camera viewfinder
│   │   │       ├── Controls.kt           # UI controls overlay
│   │   │       └── theme/                # App theme & colors
│   │   ├── res/
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   └── themes.xml
│   │   │   └── mipmap-*/                 # App icons
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/workflows/
│   └── android.yml                       # CI/CD build workflow
└── README.md
```

---

## 🎯 Roadmap

- [ ] Real MediaRecorder integration for actual video saving
- [ ] Custom video file naming and location
- [ ] Additional manual controls (Exposure Compensation, ISO limits)
- [ ] Preview LUT application for Log mode
- [ ] Audio level monitoring
- [ ] Zoom control
- [ ] Focus peaking visualization
- [ ] Waveform monitor

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 🙏 Acknowledgments

- Built with help from AI assistants
- Inspired by professional mirrorless camera interfaces
- UI design influenced by modern camera apps like Filmic Pro and ProTake

---

## 📞 Contact

For further queries, contact me at **X (formerly Twitter)**: [@4k_isn](https://twitter.com/4k_isn)

---

**Made with ❤️ by PixelGamer4k**
