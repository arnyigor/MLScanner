# MLScanner

Android OCR/scanner app with ML Kit, Tesseract and ZXing.

## OCR Engines

- **ML Kit** — Google ML Kit Text Recognition v2
- **Tesseract** — Tesseract OCR 5.x с поддержкой русского и английского языков
- **ZXing** — Barcode scanning
- **Paddle OCR** — *Пробовали использовать, но модель не подходит для качественного распознавания русского языка.*

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