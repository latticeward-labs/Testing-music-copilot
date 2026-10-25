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
import com.k2fsa.sherpa.onnx.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
  private val dir = "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27"
  private val executor = Executors.newSingleThreadExecutor()
  private val report = StringBuilder()
  private var model: OfflineRecognizer? = null
  @Volatile private var loading = true
  @Volatile private var capture = false
  @Volatile private var destroyed = false
  private lateinit var status: TextView
  private lateinit var transcript: TextView
  private lateinit var logView: TextView
  private lateinit var grant: Button
  private lateinit var start: Button
  private lateinit var stop: Button
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(20, 25, 20, 20)
      setBackgroundColor(Color.rgb(11,18,32))
    }
    setContentView(root)
    fun label(v: String, size: Float) = TextView(this).apply {
      text = v
      textSize = size
      setTextColor(Color.WHITE)
      setPadding(6,8,6,8)
    }
    fun button(v: String) = Button(this).apply { text = v; isAllCaps = false }
    root.addView(label("Moonshine Tiny • Offline STT", 22f))
    root.addView(label("English; model in APK; no Internet", 12f))
    status = label("Loading model...", 16f)
    root.addView(status)
    grant = button("1) ALLOW MICROPHONE")
    start = button("2) START RECORDING")
    stop = button("3) STOP AND DECODE")
    root.addView(grant)
    root.addView(start)
    root.addView(stop)
    transcript = label("Speak: Play the music", 20f)
    root.addView(transcript)
    root.addView(button("COPY REPORT").apply {
      setOnClickListener {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
          .setPrimaryClip(ClipData.newPlainText("Moonshine diagnostics", report.toString()))
      }
    })
    root.addView(label("Diagnostic event log", 13f))
    val scroll = ScrollView(this)
    logView = label("", 12f).apply { setTextIsSelectable(true) }
    scroll.addView(logView)
    root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
    grant.setOnClickListener {
      if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 200)
      else log("MIC_ALREADY_GRANTED")
    }
    start.setOnClickListener { startCapture() }
    stop.setOnClickListener { log("STOP_PRESSED"); capture = false }
    log("START_APP Moonshine Tiny v2; Sherpa-ONNX 2.0.0")
    log("OFFLINE_ONLY; INTERNET_PERMISSION=NONE; Android API=" + android.os.Build.VERSION.SDK_INT)
    update()
    executor.execute {
      val t = SystemClock.elapsedRealtime()
      try {
        val config = OfflineRecognizerConfig(
          featConfig = FeatureConfig(sampleRate=16000, featureDim=80),
          modelConfig = OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
              encoder = dir + "/encoder_model.ort",
              mergedDecoder = dir + "/decoder_model_merged.ort"
            ),
            tokens = dir + "/tokens.txt",
            numThreads = 2,
            debug = false
          )
        )
        model = OfflineRecognizer(assetManager = assets, config = config)
        log("MODEL_READY elapsed_ms=" + (SystemClock.elapsedRealtime()-t))
        message("Ready • fully offline")
      } catch(e: Throwable) {
        log("MODEL_ERROR " + e.javaClass.name + " " + e.message)
        message("MODEL ERROR • see report")
      } finally { loading = false; update() }
    }
  }
  private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  private fun update() = runOnUiThread {
    if (destroyed) return@runOnUiThread
    grant.text = if (hasMic()) "MICROPHONE ALLOWED" else "1) ALLOW MICROPHONE"
    start.isEnabled = hasMic() && !loading && !capture && model != null
    stop.isEnabled = capture
  }
  private fun message(v:String) = runOnUiThread { if(!destroyed) status.text=v }
  private fun log(v:String) = runOnUiThread {
    if(destroyed) return@runOnUiThread
    val time=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date())
    report.append("[").append(time).append("] ").append(v).append('\n')
    logView.text=report.toString()
  }
  override fun onRequestPermissionsResult(code:Int, names:Array<out String>, results:IntArray) {
    super.onRequestPermissionsResult(code,names,results)
    log("MIC_PERMISSION=" + if(hasMic()) "GRANTED" else "DENIED")
    update()
  }
  private fun startCapture() {
    if(!hasMic() || loading || model==null || capture) {
      log("CAPTURE_REJECTED")
      return
    }
    capture=true
    transcript.text="Listening..."
    message("Recording • press Stop")
    update()
    executor.execute {
      var audio:AudioRecord?=null
      val began=SystemClock.elapsedRealtime()
      try {
        val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(min>0) {"Invalid buffer size: "+min}
        audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
          AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,min*2)
        require(audio.state==AudioRecord.STATE_INITIALIZED) {"AudioRecord could not initialize"}
        audio.startRecording()
        require(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING) {"Recording could not start"}
        log("CAPTURE_STARTED")
        val segments=ArrayList<FloatArray>()
        val buf=ShortArray(1600)
        var total=0
        while(capture && !destroyed && SystemClock.elapsedRealtime()-began<12000) {
          val n=audio.read(buf,0,buf.size)
          require(n>=0) {"AudioRecord read failure: "+n}
          if(n>0) {
            segments.add(FloatArray(n) { i -> buf[i]/32768.0f })
            total+=n
          }
        }
        capture=false
        audio.stop();audio.release();audio=null
        log("CAPTURE_ENDED sample_count="+total)
        if(!destroyed && total>4000) {
          message("Decoding locally...")
          val samples=FloatArray(total)
          var offset=0
          for(s in segments) { s.copyInto(samples,offset); offset+=s.size }
          val r=requireNotNull(model)
          val stream=r.createStream()
          val t=SystemClock.elapsedRealtime()
          try {
            stream.acceptWaveform(samples, sampleRate=16000)
            r.decode(stream)
            val text=r.getResult(stream).text.trim()
            log("DECODE_MS="+(SystemClock.elapsedRealtime()-t))
            log(if(text.isNotEmpty()) "RECOGNITION_SUCCESS text="+text else "EMPTY_RESULT")
            runOnUiThread { transcript.text=if(text.isNotEmpty())text else "(No words recognized)" }
            message(if(text.isNotEmpty())"SUCCESS" else "No recognized speech")
          } finally { stream.release() }
        } else if(!destroyed) message("Too little audio")
      } catch(e:Throwable) {
        log("ASR_ERROR "+e.javaClass.name+": "+e.message)
        message("ERROR • copy report")
      } finally {
        capture=false
        try { audio?.stop() } catch(e:Exception) {}
        try { audio?.release() } catch(e:Exception) {}
        update()
      }
    }
  }
  override fun onStop() { capture=false; super.onStop() }
  override fun onDestroy() { destroyed=true; capture=false; executor.shutdown(); super.onDestroy() }
}
