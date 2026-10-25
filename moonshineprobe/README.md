# Moonshine Tiny + Silero VAD offline STT probe (v1.1)

**Independent test app** in latticeward-labs/Testing-music-copilot. Music Copilot production repository is unchanged.

- Built-in Moonshine Tiny English v2 quantized ONNX + bundled Silero ONNX VAD, both run **entirely on-device** through Sherpa-ONNX 2.0.0. No INTERNET permission.
- ARM64 Android 10+, model downloaded only by GitHub Actions at APK build time. Install APK and use in airplane mode.
- Tap **TAP TO SPEAK** once. Silero VAD reads 512-sample PCM frames at 16 kHz, detects speech and ends after 0.55 s of silence (min speech 0.18 s); automatically hands the segmented speech to Moonshine for offline decoding.
- If there is no speech, exits after 5 seconds. Hard cap of 12 seconds for a session. Explicit CANCEL (not required to finish a normal command).
- Timestamped diagnostic report includes start/speech end, recording time, decoding time and total response time. This allows isolating the bottleneck: VAD cuts dead air but does not make the Moonshine model's inference intrinsically faster.
- Contains **no automated tests**; GitHub Actions only assembles the APK. Actual functionality evaluated by user on phone.

### Phone QA
1. Install APK, grant mic; turn off all network connections.
2. Tap TAP TO SPEAK, say "Play the music", then remain quiet. **Do not tap Cancel.** Expect auto stop and transcript.
3. Try "Pause music", "Next song", "Previous song", and "Stop music".
4. Check a silent/no-speech session (should finish after ~5 sec).
5. Check background-noise conditions, compare ASR_DECODE_MS and TOTAL_RESPONSE_MS with prior build.
6. If failure, use COPY DIAGNOSTIC REPORT.

Build workflow: .github/workflows/build-moonshine-tiny-apk.yml.
Models: Moonshine sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27 and https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx.
