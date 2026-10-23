# WebrtcPhone-Android (ScrcpyOverWebRTC Mobile Client)

[![Android CI](https://github.com/hqw700/WebrtcPhone-Android/actions/workflows/build.yml/badge.svg)](https://github.com/hqw700/WebrtcPhone-Android/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue.svg)](https://kotlinlang.org)
[![Android SDK](https://img.shields.io/badge/minSdk-24%20%7C%20compileSdk-35-brightgreen.svg)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.12.01-purple.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[中文](README.md) | English

**WebrtcPhone-Android (穿云投屏)** is a modern native Android client engineered for high-performance cloud phone and physical Android device fleet management, powered by WebRTC and a customized `scrcpy-server` agent.

This project serves as the official mobile controller of the [ScrcpyOverWebRTC Ecosystem](https://github.com/hqw700/ScrcpyOverWebRTC). Connecting seamlessly to the signaling server, it provides ultra-low latency hardware-accelerated video streaming, fine-grained multi-touch remote control, dual-mode file management, batch APK installation, remote terminal access, and device lease scheduling across ARM64/x86 physical devices and containerized cloud phones (redroid / AOSP).

---

## 🔗 Ecosystem & Related Projects

- **Web Management Console & Backend Services**: 👉 [ScrcpyOverWebRTC](https://github.com/hqw700/ScrcpyOverWebRTC)  
  Offers clientless browser access, dashboard device matrix, surveillance console, signaling relay, and all-in-one Docker deployment images.
- **Official Documentation & Portal**: 👉 [webrtc-phone.com](https://webrtc-phone.com) / [Documentation](https://webrtc-phone.com/docs/)

---

## 📱 Screenshots

<p align="center">
  <img src="docs/snapshot/1.png" width="31%" alt="Device Matrix Dashboard" />
  <img src="docs/snapshot/2.png" width="31%" alt="Low Latency Direct Control" />
  <img src="docs/snapshot/3.png" width="31%" alt="In-Stream Device Drawer" />
</p>
<p align="center">
  <em>Figure 1: Device Matrix Dashboard &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 2: Ultra-low latency stream (10ms) &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 3: In-stream device switching drawer</em>
</p>

<p align="center">
  <img src="docs/snapshot/5.png" width="31%" alt="WebADB Interactive Terminal" />
  <img src="docs/snapshot/6.png" width="31%" alt="Dual-Mode File Manager" />
  <img src="docs/snapshot/8.png" width="31%" alt="User & Lease Management" />
</p>
<p align="center">
  <em>Figure 4: WebADB interactive PTY terminal &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 5: Dual-mode file manager &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 6: User management & lease scheduling</em>
</p>

<details>
<summary><b>🔍 Click to view more screenshots (Login / Control Panel / Batch Dispatch / Management Center / Settings)</b></summary>
<br>
<p align="center">
  <img src="docs/snapshot/10.png" width="31%" alt="Authentication" />
  <img src="docs/snapshot/4.png" width="31%" alt="Control Panel" />
  <img src="docs/snapshot/9.png" width="31%" alt="Batch File Dispatch" />
</p>
<p align="center">
  <em>Figure 7: Secure login & auth &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 8: Full control panel overlay &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 9: Batch file dispatch wizard</em>
</p>
<p align="center">
  <img src="docs/snapshot/7.png" width="31%" alt="Management Center" />
  <img src="docs/snapshot/0.png" width="31%" alt="Settings & Preferences" />
</p>
<p align="center">
  <em>Figure 10: Fleet management center &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp; Figure 11: Settings & stream preferences</em>
</p>
</details>

---

## ✨ Key Features

- **Ultra-Low Latency Dual-Channel Streaming**:
  - **WebRTC P2P Direct**: Hardware-accelerated direct decoding over DTLS-SRTP media channels with end-to-end latency as low as 30~50ms.
  - **WebSocket H.264 Fallback**: Pure TCP WebSocket stream decoding designed to bypass strict enterprise firewalls and restrictive NAT configurations.
- **Comprehensive Immersive Controls**:
  - Precise multi-touch gesture mappings (pinch-to-zoom, rotate, multi-finger swipe).
  - Physical navigation key injection (Back, Home, Power, Volume, Quick Settings drawer, lock screen, and screenshots).
  - Silent bidirectional clipboard synchronization and 100% reliable non-ASCII text injection (Unicode / Chinese IME).
- **Fleet Dashboard & Instant Device Switching**:
  - Matrix view of active device thumbnails with dynamic FPS, bitrate, and resolution overlays.
  - Proprietary **Device Drawer**: Seamlessly switch between active cloud devices on the fly without exiting the ongoing remote session.
- **Dual-Mode File Distribution System**:
  - **P2P Direct File Manager**: Dedicated DataChannel transfer for browsing, creating directories, renaming, and downloading files on device storage.
  - **Server-Relay Batch Tasks**: Upload once to server storage, then silently distribute and install APKs across multiple target devices simultaneously.
- **Remote Terminal & WebADB**:
  - Integrated interactive PTY command-line terminal powered by xterm.js for instant debugging and fleet operations.
- **User Permissions & Device Lease Scheduling**:
  - Multi-user RBAC (admin vs. standard user), expiration controls, and password resets.
  - One-click lease allocation, renewals, revocations, and intelligent idle device discovery.
- **End-to-End Security Architecture**:
  - Strict TLS TOFU (Trust On First Use) certificate fingerprint pinning—no trust-all security flaws.
  - Credentials and tokens stored exclusively in Android Keystore encrypted preferences (AES-256-GCM).

---

## 🛠️ Tech Stack & Architecture

- **Language**: 100% Kotlin
- **UI Toolkit**: Jetpack Compose + Material Design 3
- **Async & State Management**: Kotlin Coroutines + StateFlow + MVVM (lightweight unidirectional data flow)
- **Networking & Media**:
  - OkHttp 4 (REST API / WebSocket / TLS ClientHello fragmentation to bypass DPI)
  - Google WebRTC (libwebrtc M114+ / Hardware VideoDecoder)
  - Android MediaCodec (Low-latency AVC/H.264 decoding pipeline)
- **Secure Storage**: AndroidX Security (EncryptedSharedPreferences / Android Keystore)

### Directory Structure

```text
app/src/main/java/com/webrtc_phone/android/
├── core/             # Infrastructure: TLS pinning, Keystore encryption, network utilities
├── data/             # Data Layer: REST API client, session management, device/lease/file repositories
├── rtc/              # WebRTC & WebSocket streaming engine, signaling protocols, DataChannel handlers
└── ui/               # Presentation Layer: Jetpack Compose screens & ViewModels
    ├── common/       # Shared UI components and design tokens
    ├── home/         # Device matrix dashboard, management tabs
    ├── login/        # Authentication & TLS certificate confirmation
    ├── manage/       # Batch dispatch, user management, device leases, tag manager
    ├── settings/     # Video quality settings, stream preferences & about dialog
    └── stream/       # Immersive remote control, floating bubble, device drawer, terminal & file panels
```

---

## 🚀 Building from Source

### 1. Requirements

- **JDK**: 17 or higher
- **Android SDK**: compileSdk 35 / minSdk 24
- **Gradle**: 8.10.2 (Gradle wrapper included)

### 2. Compilation & Verification

```bash
# Clone the repository
git clone https://github.com/hqw700/WebrtcPhone-Android.git
cd WebrtcPhone-Android

# Verify Kotlin compilation
./gradlew compileDebugKotlin

# Run unit tests
./gradlew testDebugUnitTest

# Assemble Debug APK
./gradlew assembleDebug
```

Built APK outputs:
`app/build/outputs/apk/debug/WebrtcPhone-v<version>-debug.apk`

---

## 🔑 Release Signing & Packaging

To protect private credentials, keystore files are excluded from git. You can configure release signing using either of the following approaches:

### Approach A: Local `local.properties` (Recommended for Local Dev)

Add signing credentials to `local.properties` (ignored by `.gitignore`):

```properties
KEYSTORE_FILE=/path/to/your/release.keystore
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

Then build the release bundle:
```bash
./gradlew assembleRelease
```

### Approach B: Environment Variables (Recommended for CI/CD)

```bash
export KEYSTORE_FILE=/path/to/your/release.keystore
export KEYSTORE_PASSWORD=your_keystore_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password

./gradlew assembleRelease
```

Release APK with R8 obfuscation and resource shrinking will be generated at:
`app/build/outputs/apk/release/WebrtcPhone-v<version>-release.apk`

---

## 🤖 GitHub Actions CI/CD Pipeline

The repository includes a ready-to-use GitHub Actions workflow [`.github/workflows/build.yml`](.github/workflows/build.yml):

- **Triggers**: Pushes or pull requests to `main` / `master`, and tag pushes (`v*`).
- **Automated Pipeline**:
  1. Sets up JDK 17 environment with Gradle dependency caching.
  2. Runs JVM unit tests with fail-safe test report uploads.
  3. Builds Debug APK and uploads build artifacts.
  4. Decodes Base64 keystore and builds signed Release APK when repository secrets are provided:
     - `KEYSTORE_BASE64`
     - `KEYSTORE_PASSWORD`
     - `KEY_ALIAS`
     - `KEY_PASSWORD`
  5. Automatically creates GitHub Releases and publishes APK binaries when version tags (`v*`) are pushed.

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
