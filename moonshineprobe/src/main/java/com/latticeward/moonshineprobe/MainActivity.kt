package com.latticeward.moonshineprobe

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Independent ARM64 Android STT probe:
 * microphone -> on-device Silero VAD -> Moonshine Tiny English v2.
 * No Android SpeechRecognizer, no INTERNET permission, no audio persistence.
 */
class MainActivity : Activity() {
  companion object {
    private const val MODEL_DIR = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"
    private const val SAMPLE_RATE = 16000
    private const val FRAME = 512       // 32 ms, required by Silero VAD at 16 kHz
    private const val FIRST_SPEECH_TIMEOUT_MS = 5000L
    private const val SESSION_LIMIT_MS = 12000L
    private const val MIC_PERMISSION_REQUEST = 200
  }

  private val executor = Executors.newSingleThreadExecutor()
  private val report = StringBuilder()
  private var recognizer: OfflineRecognizer? = null
  private var vad: Vad? = null
  @Volatile private var loading = true
  @Volatile private var listening = false
  @Volatile private var cancelRequested = false
  @Volatile private var destroyed = false

  private lateinit var status: TextView
  private lateinit var transcript: TextView
  private lateinit var logView: TextView
  private lateinit var allowButton: Button
  private lateinit var micButton: Button
  private lateinit var cancelButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = Color.rgb(11, 18, 32)
    window.navigationBarColor = Color.rgb(11, 18, 32)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(22, 24, 22, 16)
      setBackgroundColor(Color.rgb(11, 18, 32))
    }
    setContentView(root)
    fun label(s: String, size: Float): TextView = TextView(this).apply {
      text = s
      textSize = size
      setTextColor(Color.WHITE)
      setPadding(7, 8, 7, 8)
    }
    fun button(s: String): Button = Button(this).apply {
      text = s
      isAllCaps = false
      setTextColor(Color.WHITE)
      backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(40, 103, 191))
    }
    root.addView(label("Moonshine Tiny + Silero VAD", 22f))
    root.addView(label("Automatic speech end • English offline • ARM64", 13f))
    status = label("Initializing models…", 16f)
    root.addView(status)
    allowButton = button("1) ALLOW MICROPHONE")
    micButton = button("2) TAP TO SPEAK (AUTO STOP)")
    cancelButton = button("CANCEL LISTENING")
    root.addView(allowButton)
    root.addView(micButton)
    root.addView(cancelButton)
    root.addView(label("Recognized command:", 14f))
    transcript = label("Try: Play the music", 20f).apply { setTextIsSelectable(true) }
    root.addView(transcript)
    root.addView(button("COPY DIAGNOSTIC REPORT").apply {
      setOnClickListener {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
          ClipData.newPlainText("Moonshine VAD report", report.toString())
        )
      }
    })
    root.addView(label("Events and timing (milliseconds):", 13f))
    val scroller = ScrollView(this)
    logView = label("", 12f).apply { setTextIsSelectable(true) }
    scroller.addView(logView)
    root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(label("Only microphone permission. Model and VAD inside APK. No Internet.", 11f))

    allowButton.setOnClickListener {
      if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
      else log("MIC_ALREADY_GRANTED")
    }
    micButton.setOnClickListener { beginListening() }
    cancelButton.setOnClickListener {
      if (listening) {
        log("CANCEL_REQUESTED")
        cancelRequested = true
      }
    }

    log("APP_STARTED engine=MoonshineTiny-v2 model=quantized; VAD=Silero ONNX")
    log("SHERPA_ONNX=2.0.0; API=" + android.os.Build.VERSION.SDK_INT +
      "; ABI=" + android.os.Build.SUPPORTED_ABIS.joinToString())
    log("NETWORK_PERMISSION=NONE; SYSTEM_SPEECH_RECOGNIZER=NOT_USED; SAMPLE_RATE=16000")
    log("VAD_CONFIG threshold=0.5; min_speech_s=0.18; silence_end_s=0.55; frame=512")
    log("ENDPOINT_POLICY first_speech_timeout_ms=5000; total_limit_ms=12000")
    refresh()
    executor.execute { initializeEngines() }
  }

  private fun initializeEngines() {
    val start = SystemClock.elapsedRealtime()
    try {
      val config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
          moonshine = OfflineMoonshineModelConfig(
            encoder = MODEL_DIR + "/encoder_model.ort",
            mergedDecoder = MODEL_DIR + "/decoder_model_merged.ort"
          ),
          tokens = MODEL_DIR + "/tokens.txt",
          numThreads = 2,
          debug = false
        )
      )
      recognizer = OfflineRecognizer(assetManager = assets, config = config)
      log("MOONSHINE_READY init_ms=" + (SystemClock.elapsedRealtime() - start))
      val vadConfig = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
          model = "silero_vad.onnx",
          threshold = 0.5f,
          minSilenceDuration = 0.55f,
          minSpeechDuration = 0.18f,
          windowSize = FRAME,
          maxSpeechDuration = 8.0f
        ),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
        debug = false
      )
      vad = Vad(assetManager = assets, config = vadConfig)
      log("SILERO_VAD_READY total_init_ms=" + (SystemClock.elapsedRealtime() - start))
      showStatus("Ready — tap microphone and speak")
    } catch (e: Throwable) {
      log("INIT_ERROR " + e.javaClass.name + " message=" + e.message)
      showStatus("Model initialization failed — copy report")
    } finally {
      loading = false
      refresh()
    }
  }

  private fun beginListening() {
    if (!hasMic() || loading || listening || recognizer == null || vad == null) {
      log("START_REJECTED mic=" + hasMic() + " ready=" +
        (recognizer != null && vad != null) + " listening=" + listening)
      if (!hasMic()) showStatus("Grant microphone permission first")
      return
    }
    cancelRequested = false
    listening = true
    transcript.text = "Listening… speak a short English command"
    showStatus("Listening • stops automatically after you finish")
    refresh()
    executor.execute { listenAndRecognize() }
  }

  private fun listenAndRecognize() {
    var recorder: AudioRecord? = null
    val sessionStart = SystemClock.elapsedRealtime()
    var detectedSpeech = false
    var decoded = false
    var capturedSamples = 0L
    try {
      val activityVad = requireNotNull(vad)
      activityVad.reset()
      val minBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
      )
      require(minBuffer > 0) { "AudioRecord minimum buffer invalid: " + minBuffer }
      recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, max(minBuffer * 2, FRAME * 8)
      )
      require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
      recorder.startRecording()
      require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        "AudioRecord did not start"
      }
      log("MIC_CAPTURE_STARTED ts_ms=" + sessionStart)
      val pcm = ShortArray(FRAME)
      var stopReason = "SESSION_TIMEOUT"
      // Feed VAD exactly 512 samples at a time. The model holds short pre-roll,
      // detects speech onset, and returns a finished speech segment after silence.
      while (!cancelRequested && !destroyed) {
        val elapsed = SystemClock.elapsedRealtime() - sessionStart
        if (elapsed >= SESSION_LIMIT_MS) {
          stopReason = "MAX_SESSION_DURATION"
          break
        }
        if (!detectedSpeech && elapsed >= FIRST_SPEECH_TIMEOUT_MS) {
          stopReason = "NO_SPEECH_TIMEOUT"
          break
        }
        var filled = 0
        while (filled < FRAME && !cancelRequested && !destroyed) {
          val count = recorder.read(pcm, filled, FRAME - filled, AudioRecord.READ_BLOCKING)
          require(count >= 0) { "AudioRecord.read returned " + count }
          if (count == 0) continue
          filled += count
        }
        if (filled != FRAME || cancelRequested || destroyed) break
        capturedSamples += FRAME
        val samples = FloatArray(FRAME) { i -> pcm[i] / 32768.0f }
        activityVad.acceptWaveform(samples)
        if (activityVad.isSpeechDetected() && !detectedSpeech) {
          detectedSpeech = true
          log("VAD_SPEECH_STARTED elapsed_ms=" + (SystemClock.elapsedRealtime() - sessionStart))
          showStatus("Speech detected • finish your sentence")
        }
        if (!activityVad.empty()) {
          stopReason = "VAD_END_OF_SPEECH"
          break
        }
      }
      // Release mic before running CPU-heavy ASR decoding.
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
      recorder.release()
      recorder = null
      val captureMs = SystemClock.elapsedRealtime() - sessionStart
      log("MIC_CAPTURE_STOPPED reason=" +
        (if (cancelRequested) "CANCELLED" else stopReason) +
        " capture_ms=" + captureMs + " captured_audio_ms=" + (capturedSamples * 1000 / SAMPLE_RATE))

      if (cancelRequested || destroyed) {
        log("SESSION_CANCELLED")
        showStatus("Cancelled — tap microphone to try again")
        return
      }
      // If user hits the max duration while speaking, flush buffered partial utterance.
      if (activityVad.empty() && detectedSpeech) {
        activityVad.flush()
        log("VAD_FLUSH_ON_TIMEOUT segments_pending=" + !activityVad.empty())
      }
      if (!activityVad.empty()) {
        val speech = activityVad.front()
        val speechSamples = speech.samples
        val speechStart = speech.start
        activityVad.pop()
        activityVad.clear()
        log("VAD_SPEECH_ENDED speech_start_ms=" + (speechStart * 1000 / SAMPLE_RATE) +
          " speech_duration_ms=" + (speechSamples.size * 1000 / SAMPLE_RATE) +
          " silence_end_threshold_ms=550")
        showStatus("Speech complete • decoding offline…")
        val beginDecode = SystemClock.elapsedRealtime()
        val engine = requireNotNull(recognizer)
        val stream = engine.createStream()
        try {
          stream.acceptWaveform(speechSamples, sampleRate = SAMPLE_RATE)
          engine.decode(stream)
          val result = engine.getResult(stream).text.trim()
          val decodeMs = SystemClock.elapsedRealtime() - beginDecode
          log("ASR_DECODE_MS=" + decodeMs +
            "; TOTAL_RESPONSE_MS=" + (SystemClock.elapsedRealtime() - sessionStart))
          log(if (result.isEmpty()) "RECOGNITION_EMPTY" else "RECOGNITION_SUCCESS text=" + result)
          runOnUiThread { if (!destroyed) transcript.text = if (result.isEmpty()) "(No words recognized)" else result }
          showStatus(if (result.isEmpty()) "No words recognized — retry" else "Done ✓ — tap to speak again")
          decoded = true
        } finally {
          stream.release()
        }
      }
      if (!decoded) {
        log("VAD_NO_COMPLETE_SPEECH total_ms=" + (SystemClock.elapsedRealtime() - sessionStart))
        runOnUiThread { if (!destroyed) transcript.text = "(No complete speech detected)" }
        showStatus("No speech detected — tap to retry")
      }
    } catch (e: Throwable) {
      log("SESSION_ERROR " + e.javaClass.name + " message=" + e.message)
      showStatus("Recording/recognition error — copy report")
    } finally {
      listening = false
      try { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
      catch (_: Exception) {}
      try { recorder?.release() } catch (_: Exception) {}
      refresh()
    }
  }

  private fun hasMic(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  override fun onRequestPermissionsResult(
    code: Int, names: Array<out String>, outcomes: IntArray
  ) {
    super.onRequestPermissionsResult(code, names, outcomes)
    if (code == MIC_PERMISSION_REQUEST) {
      log("MIC_PERMISSION_RESULT=" + if (hasMic()) "GRANTED" else "DENIED")
      refresh()
    }
  }

  private fun refresh() = runOnUiThread {
    if (destroyed) return@runOnUiThread
    allowButton.text = if (hasMic()) "MICROPHONE ALLOWED ✓" else "1) ALLOW MICROPHONE"
    micButton.isEnabled = hasMic() && !loading && !listening && recognizer != null && vad != null
    cancelButton.isEnabled = listening
  }

  private fun showStatus(value: String) = runOnUiThread {
    if (!destroyed) status.text = value
  }

  private fun log(value: String) = runOnUiThread {
    if (destroyed) return@runOnUiThread
    val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    report.append("[").append(now).append("] ").append(value).append('\n')
    logView.text = report.toString()
  }

  override fun onStop() {
    cancelRequested = true
    super.onStop()
  }

  override fun onDestroy() {
    destroyed = true
    cancelRequested = true
    executor.shutdown()
    super.onDestroy()
  }
}
