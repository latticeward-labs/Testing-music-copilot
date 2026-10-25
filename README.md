# Voice Probe V2 — standalone Android speech diagnostic

This test application lives ONLY in the `Testing-music-copilot` repository. It does not change the production Music Copilot repo.

## Tests
- **1) TEST SYSTEM DEFAULT STT**: Android's default provider; provider may use the network.
- **2) TEST ON-DEVICE ONLY STT**: strictly offline provider (Android 12+).
- **3) REQUEST OFFLINE MODEL DOWNLOAD**: Android 13+ system API to request the selected offline language.
- Pick en-US, grant microphone permission, run both modes saying "Play the music", then tap **Copy report**.
- Review timestamped callbacks, provider mode, transcript, installed/downloadable languages, and error codes.

Only `RECORD_AUDIO` permission. No app INTERNET permission or backend. Explicit button press required for capture.

## APK
GitHub Actions builds `:app:assembleDebug` only (**no tests or lint**). Open the newest **Build Voice Probe V2 APK** run in Actions and download artifact **Voice-Probe-V2-Android-APK**. Unzip for `Voice-Probe-V2-debug.apk`.

Package `com.latticeward.sttprobe`, updated test app only (not Music Copilot).
