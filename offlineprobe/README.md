# Offline Vosk STT Probe (Independent Android APK)
This is an **isolated test APK** built only in \`latticeward-labs/Testing-music-copilot\`.
It **does not modify Music Copilot** and is a separate Android package \`com.latticeward.offlinevoskprobe\`.

## How this works
- Uses native offline Vosk engine v0.3.75, **not** Android SpeechRecognizer.
- Includes Apache-2.0 licensed \`vosk-model-small-en-us-0.15\` inside APK.
- Does not request INTERNET permission, no backend and no model download on the phone.
- First launch extracts the bundled model to app-specific on-device files and initializes it.
- Uses the microphone only after tapping START; output is visible on screen.
- Timestamps, model startup, errors, partial/final recognized text can be copied.
- Audio is not saved or uploaded.

## Build
\`.github/workflows/build-offline-stt-apk.yml\` downloads the publicly published model **during GitHub Actions build only**, unpacks to local assets, builds \`:offlineprobe:assembleDebug\`, uploads APK in Actions. **No automated tests or lint**.

Model source: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
Model license: Apache 2.0 https://alphacephei.com/vosk/models
Vosk dependency: https://central.sonatype.com/artifact/com.alphacephei/vosk-android/0.3.75

## Manual phone test
1. Install APK, grant microphone. Allow time for bundled model first-run extraction and load.
2. Turn off Wi-Fi and mobile data.
3. Press \`START OFFLINE STT\`, say "Play the music", then \`STOP AND FINALIZE\`.
4. Check for \`RECOGNITION_SUCCESS\` in log and copy full report.
This verifies only STT, not Music Copilot integration.

## V1.1 - Smaller ABI-specific builds
- ARM64 APK: `Offline-Vosk-STT-Probe-arm64-v8a.apk` for 64-bit Android phones.
- ARM32 APK: `Offline-Vosk-STT-Probe-armeabi-v7a.apk` for supported older devices.
- Speech model and recognizer logic unchanged. Only irrelevant native ABIs excluded.
- Artifact: `Offline-Vosk-STT-Probe-Optimized-APK`. GitHub Actions builds only; no tests or lint.
