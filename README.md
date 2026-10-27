# Kinet ኪ

> **Tactile, on-device AI stem separator and remix studio for Android.**  
> Split songs into vocals, drums, bass, and instruments entirely offline with hardware-grade precision.

---

## Overview

**Kinet** is a modern music player and stem separation workstation built for musicians, producers, DJs, and audio enthusiasts. Powered by quantized on-device neural networks, Kinet isolates audio tracks without ever uploading a single byte to the cloud.

The interface is inspired by high-end analog audio equipment: tactile warm stone chassis, dark hardware carbon, terracotta accents, precision vernier rollers, and zero decorative bloat.

---

## Features

### 🎛️ On-Device AI Stem Separation
- **Dual & Quad Separation Modes**:
  - **2-Stem (Fast)**: Isolate **Vocals** and **Accompaniment** in seconds.
  - **4-Stem (Studio)**: Isolate **Vocals**, **Drums**, **Bass**, and **Other** with high fidelity.
- **Dynamic In-Player Upgrade**: Process a quick 2-stem mix first, then upgrade seamlessly to full 4-stem separation on demand.
- **100% Offline & Private**: Neural models (FP16 ONNX) run locally on device via multi-threaded CPU inference. No accounts, no subscriptions, no internet connection required.

### 🎚️ Tactile Hardware Channel Strips
- **Individual Stem Faders**: Smooth decibel-attenuated volume controls for each stem.
- **Solo & Mute**: Instant isolation toggles (`S` / `M`) with visual LED-style indicators.
- **Color-Coded Glyphs**: Purpose-crafted audio iconography for Vocals, Drums, Bass, Instrumental, and Other channels.

### 🔄 Precision 3D Pitch Roller & Looper
- **Magnetic Cylindrical Pitch Roller**: Natural, physical roll gesture with haptic click feedback for transposing pitch across ±6 semitones in real time.
- **Integrated Loop Capsule**: Hardware segmented controller (`[ A | B | To A | Off ]`) for instantaneous phrase looping and practice.
- **Real-Time Chord Detection**: Automatic harmonic analysis displaying chords synchronized with playback and transposition.

### 📻 Background Playback & Media Notification
- **Android MediaSession Integration**: Full background playback with audio focus management (gracefully ducks/pauses during incoming phone calls).
- **Rich Notification Bar**: Lock screen controls including ±5s seek buttons, play/pause, stem state, and Android system media timeline.

### 💾 High-Fidelity Audio Export
- Export individual isolated stems or custom mixdowns directly to your device storage as uncompressed WAV / audio files.

---

## Technical Specifications

| Parameter | Specification |
|---|---|
| **Minimum OS** | Android 11 (API level 30) |
| **Target OS** | Android 16 (API level 36) |
| **Architectures** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| **Neural Engine** | ONNX Runtime Android (`v1.17+`) |
| **Model Precision**| Quantized FP16 Local Weights |
| **Audio Pipeline** | 44.1 kHz Stereo Float32 Multi-Player Engine |
| **UI Framework** | 100% Jetpack Compose (Material 3) |

---

## Building from Source

### Prerequisites
- **JDK 21** (e.g. Eclipse Temurin 21)
- **Android SDK** (API 36, Build Tools 36.0.0)
- Git

### Build Instructions

1. **Clone the repository**:
   ```bash
   git clone https://github.com/henacodes/Kinet.git
   cd Kinet
   ```

2. **Assemble Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Assemble Release APKs** (generates universal and ABI-optimized APKs in `app/build/outputs/apk/release/`):
   ```bash
   ./gradlew assembleRelease
   ```

4. **Build Android App Bundle** (`.aab` for Play Store distribution):
   ```bash
   ./gradlew bundleRelease
   ```

---

## Automated Releases (CI/CD)

Kinet uses GitHub Actions to build and release production APKs and App Bundles automatically:
- Every push to a release tag (e.g., `v0.2.1` or `0.2.1`) triggers `.github/workflows/release.yml`.
- The workflow compiles, minifies with R8, packages ABI splits, generates `SHA256SUMS.txt`, and creates a GitHub Release with all artifacts attached:
  - `kinet-<tag>-arm64-v8a.apk`
  - `kinet-<tag>-universal.apk`
  - `kinet-<tag>-armeabi-v7a.apk`
  - `kinet-<tag>-x86_64.apk`
  - `kinet-<tag>.aab`

---

## Privacy & Security

Kinet is designed with a strict privacy-first architecture:
- **No Analytics / No Tracking**: No external analytics SDKs are embedded.
- **Local Neural Processing**: Audio processing never leaves the phone's memory.
- **Crash Safety**: In the rare event of an unexpected termination, crash diagnostics are shown locally in an emergency crash dialogue with an option to report directly via email or Telegram.

---

## License

Created by **Kirakos** ([@henacodes](https://github.com/henacodes)). All rights reserved.
Distributed under the [MIT License](LICENSE).
