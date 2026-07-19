<div align="center">
  <h1>📸 Screenshot Bubble</h1>
  <p><strong>Capture from anywhere</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin">
    <img src="https://img.shields.io/badge/Min%20SDK-26-orange?style=flat&logo=android&logoColor=white" alt="Min SDK">
    <img src="https://img.shields.io/badge/Target%20SDK-34-brightgreen?style=flat&logo=android&logoColor=white" alt="Target SDK">
    <img src="https://img.shields.io/badge/License-MIT-blue?style=flat" alt="License">
    <img src="https://img.shields.io/badge/Version-1.0.0-blueviolet?style=flat" alt="Version">
  </p>
</div>

---

## ✨ Features

- **Floating Bubble** — A draggable overlay icon that docks to the screen edge, accessible from any app
- **Instant Screenshot** — Tap the bubble to capture the screen immediately
- **Delayed Capture** — Choose 3s, 5s, or 10s countdown before capture
- **Magnetic Zones** — Drag-and-snap to 6 zones (top/middle/bottom × left/right)
- **Position Persistence** — Icon position is saved and restored across sessions
- **Auto Re-dock** — Icon snaps back to the nearest edge on release
- **Visual Feedback** — Screen flash, shutter sound, haptic vibration, and a checkmark popup
- **Thumbnail Preview** — A small preview of the captured screenshot appears near the bubble
- **Edge Peek Animation** — Periodically slides inward to remind you it's there (every 18s)
- **Breathing Animation** — Subtle idle pulse when the icon is docked
- **Light & Dark Theme** — Follows system theme or your preference

---

## 🚀 Getting Started

### Prerequisites

- Android device running **Android 8.0 (API 26)** or higher
- USB debugging enabled (for installation via ADB)

### Installation

```bash
# Clone the repository
git clone https://github.com/Demo7Vlizer/ScreenShot_Bubble-Kotlin.git

# Build and install (device must be connected)
cd ScreenShot_Bubble-Kotlin
./gradlew installDebug
```

### First Run

1. Grant **Overlay Permission** when prompted
2. Grant **Screen Capture Permission** when prompted
3. Tap **Start** to launch the floating service

---

## 📖 Usage

| Action | How |
|--------|-----|
| **Take screenshot** | Tap the floating bubble once |
| **Move bubble** | Drag the bubble to any position |
| **Auto-dock** | Release — it snaps to the nearest edge zone |
| **Delayed capture** | Set delay in dashboard → Screenshot Mode |
| **Stop service** | Tap **Stop** in the dashboard or pull down the notification → **Stop** |
| **Change theme** | Dashboard → Appearance → Light / Dark / System |

---

## 🏗️ Architecture

```
com.screenshotbubble/
├── MainActivity.kt           # Dashboard with permissions and settings
├── service/
│   ├── ScreenshotService.kt  # Foreground service managing the overlay
│   └── StopServiceReceiver.kt
├── capture/
│   ├── ScreenshotCapture.kt  # MediaProjection + VirtualDisplay capture
│   ├── ScreenshotSaver.kt    # Saves to MediaStore
│   └── ScreenshotResult.kt
├── floating/
│   ├── FloatingWidgetManager.kt  # Overlay coordinator
│   ├── DragHandler.kt            # Touch, drag, and magnetic snap logic
│   ├── ToolbarController.kt      # Expandable floating toolbar
│   ├── CountdownOverlay.kt       # Delayed capture countdown UI
│   ├── PositionPersistence.kt    # Save/restore icon position
│   └── ...
└── features/
    ├── FeatureModule.kt          # Extensible feature interface
    └── modules/                  # Screenshot, ScreenRecord, OCR, etc.
```

---

## 🛠️ Tech Stack

| | |
|---|---|
| **Language** | Kotlin 100% |
| **UI** | Material 3, ViewBinding |
| **Min / Target SDK** | 26 / 34 |
| **Screen Capture** | `MediaProjection` + `VirtualDisplay` + `ImageReader` |
| **Overlay** | `WindowManager` with `TYPE_APPLICATION_OVERLAY` |
| **Storage** | `MediaStore` via `ContentResolver` |
| **Animations** | `View.animate()`, `ObjectAnimator`, `ValueAnimator` |
| **Persistence** | `SharedPreferences` |
| **Concurrency** | `Handler` + `HandlerThread` + `Looper` |
| **Build** | Gradle 8.5 + AGP 8.2.2 |

### Dependencies

```kotlin
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
```

Zero external network calls, analytics, or third-party SDKs.

---

## 🧪 Upcoming Features

These modules are scaffolded and ready for implementation:

- [ ] Screen Recording
- [ ] Long Screenshot (Scrolling Capture)
- [ ] OCR Text Capture
- [ ] Settings Panel

---

## 📄 License

```
MIT License

Copyright (c) 2024 Screenshot Bubble

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files...
```

---

<div align="center">
  Made with ☕ and Kotlin
</div>
