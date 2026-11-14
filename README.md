# TigerBeats Studio V9.4 — ARMaco MG Edition 🎵⚡

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android SDK](https://img.shields.io/badge/Android-API%2024%2B-brightgreen.svg)](https://developer.android.com/)
[![Build Status](https://img.shields.io/badge/Build-Passing-success.svg)](#-building-the-project)
[![Audio Engine](https://img.shields.io/badge/Engine-64bit%20Anti--Shatter%20DSP-cyan.svg)](#-audio-dsp--playback-engine)

**TigerBeats Studio V9.4 (ARMaco MG Edition)** is a high-performance Android Digital Audio Workstation (DAW), YouTube MP3/MP4 Converter, 3D Spatial Audio Processor, and Custom Music Player built for Android devices.

---

## 🌟 Key Features

### ⚡ 1. On-Device yt-dlp + FFmpeg Converter
* **High-Speed Conversion**: Download and convert YouTube videos directly on your Android device without cloud servers.
* **Format Options**:
  * **YouTube → MP3**: Audio extraction at up to 320 kbps.
  * **YouTube → MP4 (720p/1080p)**: Download video and audio streams seamlessly merged into a single MP4 container via FFmpeg.
* **Auto-Updating Core**: Built-in automatic fallback engine updates to handle YouTube's latest bot protection and PO Token checks.

### 💿 2. Realistic Vinyl Player & Physics Tonearm
* **60 FPS Hardware-Accelerated Visualizer**: Interactive spinning vinyl record with live neon FFT waveform bars.
* **Realistic Tonearm Tracking**: Smooth physics-based tonearm movement that positions the stylus needle directly onto the outer grooves of the spinning vinyl disc during playback.

### 🌌 3. Ambient Visual Themes
* 🖤 **Obsidian 3D**: Ultra-sleek dark glassmorphism interface with neon cyan accents.
* 🌸 **Creamy Pink**: Floating Sakura blossom particle canvas visible through translucent glass panels.
* 👑 **Royal Gold**: Deluxe gold-amber studio aesthetic with high-contrast UI controls.
* 🌿 **Cyber Violet (Jugnu Fireflies)**: 5 peaceful glowing green Jugnu (fireflies) drifting across the **entire 100% screen** with 3D Z-axis depth scaling and audio-reactive green glowing auras.
* 🤖 **Obsidian Fury Easter Egg**: Secret Jaeger Reactor modal reveal unlocked via a triple-tap gesture on the signature heart icon.

### 🎧 4. Audio DSP & Playback Engine
* **Armaco 3D Spatial V-CURVE**: 64-Bit Anti-Shatter DSP with headroom compensation.
* **Local Sound Profiles**: Preset preview filters including *Original Studio Master*, *Lo-Fi Chill*, *Slowed + Reverb*, *Nightcore*, *Sub-Bass Boost*, and *8D Spatial*.

### 📱 5. Deep Android System Integration
* 🖼️ **Picture-in-Picture (PiP) Mode**: Automatically enters a floating mini-player window when minimizing or switching away from the app.
* 📁 **Folder Music Library**: MediaStore data path integration that automatically groups your local device music into organized folders.
* 📂 **Native File Picker (`📂 Open`)**: Integrated `onShowFileChooser` allowing you to select and load local audio files seamlessly.
* 🎙️ **Microphone Recording (MIC REC)**: WebMedia microphone capture with `RECORD_AUDIO` permission integration.
* 📲 **Home-Screen Music Widget**: Interactive Android home-screen widget for background playback control and track updates.

---

## 🛠️ Architecture & Tech Stack

* **Language**: Java / Android SDK (Min SDK 24 / Target SDK 35).
* **UI Layer**: Web-based responsive DAW frontend served locally via `WebViewAssetLoader` (`index.html`, `tailwindcss.js`, `lame.all.js`).
* **Conversion Core**: `youtubedl-android` (`yt-dlp`) & `FFmpeg` binary integration.
* **Media Playback**: Android `MediaPlayer`, `ArmacoPlaybackService` (Foreground Media Playback Service), and `MediaSession`.

---

## 🔨 Building the Project

### Prerequisites
* **Android Studio**: Android Studio Ladybug or newer.
* **JDK**: OpenJDK 17 or Java 17.
* **Android NDK**: API level 24+.

### Build Commands

```bash
# Clone the repository
git clone https://github.com/mayankg900/MG-armaco-edition.git
cd MG-armaco-edition

# Build Debug APK
./gradlew assembleDebug

# Build Signed Production Release APK
./gradlew assembleRelease
```

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** - see the [LICENSE](LICENSE) file for details. Included third-party FFmpeg and yt-dlp binaries belong to their respective open-source copyright holders.

---

## 👤 Author & Credits

* **Developer**: Mayank Gaur ([@mayankg900](https://github.com/mayankg900))
* **Project Name**: TigerBeats Studio V9.4 — ARMaco MG Edition
