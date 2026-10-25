# Music STT Probe — independent Android voice diagnosis

This is a **separate application** built solely for diagnosing Android on-device speech recognition on a real device. Its package is `com.latticeward.sttprobe`: it does not replace or mutate the main Music Copilot application.

## What it does
1. Requests only `RECORD_AUDIO` permission through a user button. No automatic recording after the permission result.
2. Starts **on-device** Android `SpeechRecognizer.createOnDeviceSpeechRecognizer` on Android 12 (API31) and newer, from an explicit button click only.
3. Displays recognized words (English by default, optional Persian) and detailed diagnostics: OS/API, microphone grant, available on-device service, language support metadata, recognition callbacks, error codes/names, failure phase, and result alternatives/confidence.
4. Lets the owner cancel, copy the diagnostic text to the clipboard, and clear the display.
5. Collects no voice data in app files, has no `INTERNET` permission, no backend, no Media3, no AI/wake-word intent mapping, and no TTS. Reports remain in memory unless the owner explicitly copies them.

**Important:** Android 10/11 cannot use the API31 on-device-only constructor. The app reports the limitation rather than silently switching to online recognition. Android 13+ OEM services can refuse language-support metadata queries; the probe records the refusal and tries the same on-device recognizer anyway, for diagnosis.

## Build
GitHub Actions `.github/workflows/build-apk.yml` assembles **exactly one APK** with `:app:assembleDebug`; it runs **no tests or lint**. Download the `Music-STT-Probe-Android-APK` artifact from Actions, unzip, and install `Music-STT-Probe-debug.apk` on a real Android device.

## Manual diagnosis
- Launch the standalone app; grant microphone permission.
- Select **English (en-US)** for comparison to the Music Copilot Issue #10 engine.
- Tap **Start listening**, wait until the page shows recognition active, speak `Play music`.
- Read **Final transcript** or **Error code + stage**, and send a copied report if needed.
- Repeat with **Persian (fa-IR)** only if you want to determine whether that offline language pack is present.

A failure or success here isolates local STT from Media3 audio duck and the Music Copilot voice coordinator. A successful STT-only probe does **not** prove the integration path works in the main Music Copilot app.
