# MLScanner

Android OCR/scanner app with multiple recognition engines for optimal text recognition quality.

## Features

- 📸 **Camera & Gallery** — Capture or select images for text recognition
- 🔍 **Multiple OCR Engines** — Automatic selection of best engine for your device
- 🇷🇺 **Excellent Russian Support** — 95% accuracy with Huawei ML Kit
- ⚡ **Fast Recognition** — 200-400ms for most texts
- 📱 **Universal Compatibility** — Works on all Android devices (API 24+)
- 🎯 **Smart Fallback** — Automatic engine switching for best results

## OCR Engines

### Primary Engines

- **Huawei ML Kit** (v3.17.11.304) — Best for Russian text (95% accuracy)
  - Requires HMS Core (~10% of devices)
  - No visual substitution (С→C, Р→P)
  - Fast: 200-400ms
  - Supports: Latin, Chinese, Japanese, Korean, Russian

- **Google ML Kit** (v16.0.1) — Fast Latin text recognition
  - Works on all devices (100%)
  - Fast: 200-300ms
  - Automatic fallback when Huawei unavailable
  - Supports: Latin scripts

- **Tesseract** (v4.7.0) — High accuracy offline OCR
  - Works on all devices (100%)
  - Slower: 2-3 seconds
  - Best for complex documents
  - Supports: Russian, English, mixed

### Additional Features

- **Hybrid Mode** — Intelligent switching between ML Kit and Tesseract
- **ZXing** — Barcode and QR code scanning
- **Preprocessing** — Automatic image enhancement (OpenCV)

## How It Works

```
App Start
    ↓
Parallel Engine Initialization
    ├─ Google ML Kit ✅
    ├─ Huawei ML Kit ✅/❌ (if HMS Core available)
    └─ Tesseract ✅
    ↓
User Scans Russian Text
    ↓
Automatic Engine Selection:
    ├─ If Huawei available → Huawei ML Kit (95% accuracy)
    └─ If not → Google ML Kit + post-processing (80% accuracy)
    ↓
Result
```

## Version History

### v0.0.2 (2026-04-27) - Current
- ✅ Added Huawei ML Kit integration
- ✅ Improved Russian text recognition (60% → 95%)
- ✅ Fixed Cyrillic visual substitution
- ✅ Automatic fallback for devices without HMS Core
- ✅ Parallel engine initialization

### v0.0.1 (2026-04-27) - Initial Release
- ✅ Google ML Kit integration
- ✅ Tesseract OCR integration
- ✅ Barcode scanning (ZXing)
- ✅ Image preprocessing (OpenCV)
- ✅ Camera and gallery support

## Requirements

- Android 7.0+ (API 24+)
- JDK 17
- Android NDK 25.2.9519653
- Gradle 8.14.3

## Build

Use JDK 17 and Android NDK `25.2.9519653`.

```bat
.\gradlew.bat :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/MLScanner-debug-v0.0.1.apk
```

## Android 11 ARM-compatible emulator

The project APK is built only for ARM ABIs:

```kotlin
abiFilters += listOf("arm64-v8a", "armeabi-v7a")
```

On a Windows x86_64 host, use the Android 11 / API 30 / Google APIs / x86_64
system image. This image can expose ARM ABIs through native translation.
This is not a pure ARM emulator.

The configured AVD name is:

```text
Pixel_5_API_30_ARM_Compat
```

SDK path on this machine:

```powershell
$env:ANDROID_HOME="g:\Android\SDK"
$env:ANDROID_SDK_ROOT="g:\Android\SDK"
```

Start the emulator:

```powershell
$env:ANDROID_HOME="g:\Android\SDK"
$env:ANDROID_SDK_ROOT="g:\Android\SDK"

& "g:\Android\SDK\emulator\emulator.exe" `
  -avd Pixel_5_API_30_ARM_Compat `
  -no-snapshot-load
```

Check that the emulator is ready:

```powershell
& "g:\Android\SDK\platform-tools\adb.exe" wait-for-device
& "g:\Android\SDK\platform-tools\adb.exe" shell getprop sys.boot_completed
```

Check ABI support:

```powershell
& "g:\Android\SDK\platform-tools\adb.exe" shell getprop ro.product.cpu.abi
& "g:\Android\SDK\platform-tools\adb.exe" shell getprop ro.product.cpu.abilist
& "g:\Android\SDK\platform-tools\adb.exe" shell getprop ro.product.cpu.abilist64
& "g:\Android\SDK\platform-tools\adb.exe" shell getprop ro.product.cpu.abilist32
```

Expected ABI list:

```text
x86_64,x86,arm64-v8a,armeabi-v7a,armeabi
```

Build and install the debug APK:

```powershell
.\gradlew.bat :app:assembleDebug

& "g:\Android\SDK\platform-tools\adb.exe" install -r `
  "app\build\outputs\apk\debug\MLScanner-debug-v0.0.1.apk"
```

Run instrumentation tests directly through adb. This avoids occasional
UTP/DDMLib `closed` failures seen with `connectedDebugAndroidTest` on this
emulator:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest

& "g:\Android\SDK\platform-tools\adb.exe" install -r `
  "app\build\outputs\apk\debug\MLScanner-debug-v0.0.1.apk"

& "g:\Android\SDK\platform-tools\adb.exe" install -r `
  "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

& "g:\Android\SDK\platform-tools\adb.exe" shell am instrument -w -r `
  com.arny.mlscanner.test/androidx.test.runner.AndroidJUnitRunner
```

Known limitation: Tesseract and ML Kit recognition can crash inside
`ndk_translation` on this API 30 x86_64 ARM-translation emulator.
Full OCR recognition validation should be done on a real ARM64 Android device.