package com.latticeward.offlineprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Totally standalone offline English ASR diagnostics. Uses a bundled Vosk model.
 * Does not use Android SpeechRecognizer, Google Speech, web APIs or Music Copilot.
 */
public final class MainActivity extends Activity implements RecognitionListener {
    private static final int REQ_MIC = 101;
    private static final int SAMPLE_RATE = 16000;
    private static final String ASSET_MODEL = "vosk-model-small-en-us-0.15";

    private TextView state, transcript, details;
    private Button allow, start, stop, copy;
    private ScrollView logScroll;
    private final StringBuilder report = new StringBuilder();
    private Model model;
    private SpeechService speech;
    private Recognizer activeRecognizer;
    private boolean loading = false;
    private boolean alive = true;
    private boolean listening = false;
    private long sessionStart;
    private String lastPartial = "";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10,16,30));
        getWindow().setNavigationBarColor(Color.rgb(10,16,30));
        makeUi();
        log("APP_STARTED: Voice Probe V3; engine=VOSK; model=vosk-model-small-en-us-0.15");
        log("SDK=" + Build.VERSION.SDK_INT + "; Android=" + Build.VERSION.RELEASE +
                "; locale=" + Locale.getDefault().toLanguageTag());
        log("NETWORK_PERMISSION=NONE; SYSTEM_SPEECH_RECOGNIZER=NOT_USED");
        log("MODEL_SOURCE=APK_ASSETS; LANGUAGE=en-US; SAMPLE_RATE=16000; MODE=OFFLINE_ONLY");
        log("MIC_PERMISSION=" + (hasMic() ? "GRANTED" : "DENIED"));
        loadModel();
        updateControls();
    }

    private void makeUi() {
        int bg = Color.rgb(10,16,30), card = Color.rgb(27,37,56);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(18),dp(22),dp(18),dp(14));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        setContentView(root);

        root.addView(text("Voice Probe V3 • Offline Vosk",24,true,Color.WHITE));
        root.addView(text("Independent English STT • no Internet • no system speech engine",
                12,false,Color.rgb(169,190,215)));
        root.addView(space(12));
        state = text("Loading English model from APK…",16,true,Color.rgb(135,225,182));
        state.setPadding(dp(12),dp(14),dp(12),dp(14));
        state.setBackgroundColor(card);
        root.addView(state);
        root.addView(space(10));
        allow = button("1) ALLOW MICROPHONE");
        start = button("2) START OFFLINE STT");
        stop = button("3) STOP AND FINALIZE");
        root.addView(allow);
        root.addView(start);
        root.addView(stop);
        allow.setOnClickListener(v -> {
            if (!hasMic()) {
                log("MIC_PERMISSION_REQUESTED_BY_USER");
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } else {
                log("MIC_PERMISSION_ALREADY_GRANTED");
                updateControls();
            }
        });
        start.setOnClickListener(v -> startRecognition());
        stop.setOnClickListener(v -> stopRecognition("USER_STOP"));

        root.addView(space(10));
        root.addView(text("Recognized English text:",13,true,Color.rgb(174,190,213)));
        transcript = text("Say: Play the music",19,false,Color.WHITE);
        transcript.setBackgroundColor(card);
        transcript.setPadding(dp(12),dp(13),dp(12),dp(13));
        transcript.setTextIsSelectable(true);
        root.addView(transcript);
        root.addView(space(10));

        copy = button("COPY FULL DIAGNOSTIC REPORT");
        root.addView(copy);
        copy.setOnClickListener(v -> {
            ClipboardManager mgr=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            mgr.setPrimaryClip(ClipData.newPlainText("Offline Vosk probe report",report.toString()));
            Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show();
        });
        root.addView(text("Live diagnostic log:",13,true,Color.rgb(174,190,213)));
        logScroll = new ScrollView(this);
        details = text("",12,false,Color.WHITE);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextIsSelectable(true);
        details.setBackgroundColor(card);
        details.setPadding(dp(10),dp(12),dp(10),dp(12));
        logScroll.addView(details);
        root.addView(logScroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(text("Only mic permission. Recognition runs on this phone; no audio upload.",
                11,false,Color.rgb(170,184,205)));
    }

    private void loadModel() {
        if (loading || model != null) return;
        loading=true;
        showState("Unpacking bundled English model on device…");
        log("MODEL_UNPACK_STARTED; model bundled inside APK (first startup may take time)");
        long started=SystemClock.elapsedRealtime();
        StorageService.unpack(this, ASSET_MODEL, "offline-vosk-model",
                result -> {
                    if (!alive) {
                        try { result.close(); } catch(Exception ignored) {}
                        return;
                    }
                    model=result; loading=false;
                    log("MODEL_READY; elapsed_ms="+(SystemClock.elapsedRealtime()-started)+
                            "; audio recognition can now run without Internet");
                    showState("OFFLINE MODEL READY ✓");
                    updateControls();
                },
                error -> {
                    if (!alive) return;
                    loading=false;
                    log("MODEL_ERROR="+error.getClass().getSimpleName()+": "+error.getMessage());
                    showState("MODEL FAILED: see log");
                    updateControls();
                });
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code==REQ_MIC) {
            log("MIC_PERMISSION_RESULT="+(hasMic()?"GRANTED":"DENIED")+
                    "; tap START manually");
            updateControls();
        }
    }

    private void startRecognition() {
        if(listening || loading || model==null || !hasMic()){
            log("START_REJECTED: model_ready="+(model!=null)+" mic="+hasMic()+
                    " already_listening="+listening);
            if(!hasMic()) showState("Grant microphone first");
            return;
        }
        lastPartial="";
        sessionStart=SystemClock.elapsedRealtime();
        log("SESSION_START: VOSK_NATIVE; OFFLINE_ONLY; language=en-US; mic=VOICE_RECOGNITION");
        try {
            activeRecognizer=new Recognizer(model,(float)SAMPLE_RATE);
            speech=new SpeechService(activeRecognizer,(float)SAMPLE_RATE);
            listening=true;
            boolean started=speech.startListening(this,20000);
            if(!started) throw new IllegalStateException("SpeechService did not start");
            log("AUDIO_CAPTURE_STARTED sample_rate="+SAMPLE_RATE+"; deadline_ms=20000");
            transcript.setText("Listening… say: Play the music");
            showState("LISTENING OFFLINE ✓");
        } catch(Exception e) {
            log("START_EXCEPTION="+e.getClass().getName()+": "+e.getMessage());
            showState("START FAILED • see log");
            cleanupSpeech(false);
        }
        updateControls();
    }

    @Override public void onPartialResult(String json) {
        if(!listening) return;
        try {
            String value=new JSONObject(json).optString("partial","");
            if(!value.isEmpty()) {
                transcript.setText(value+" …");
                if(!value.equals(lastPartial)) {
                    lastPartial=value;
                    log("PARTIAL="+value);
                }
            }
        } catch(Exception e) { log("PARTIAL_PARSE_ERROR="+e.getMessage()); }
    }

    @Override public void onResult(String json) {
        handleResult("SEGMENT",json);
    }

    @Override public void onFinalResult(String json) {
        handleResult("FINAL",json);
        log("SESSION_COMPLETE elapsed_ms="+(SystemClock.elapsedRealtime()-sessionStart));
        cleanupSpeech(true);
        showState("SESSION FINISHED • tap START to retry");
        updateControls();
    }

    private void handleResult(String kind,String json) {
        try {
            JSONObject result=new JSONObject(json);
            String recognized=result.optString("text","");
            log(kind+"_RESULT="+json);
            if(!recognized.isEmpty()) {
                transcript.setText(recognized);
                log("RECOGNITION_SUCCESS text="+recognized);
                showState("OFFLINE STT SUCCESS ✓");
            } else if(kind.equals("FINAL") && lastPartial.isEmpty()) {
                log("FINAL_EMPTY_TEXT: no recognizable words in recording");
                transcript.setText("(No recognizable words)");
            }
        } catch(Exception e) { log(kind+"_JSON_PARSE_ERROR="+e.getMessage()); }
    }

    @Override public void onError(Exception e) {
        log("RECOGNITION_ERROR="+e.getClass().getName()+": "+e.getMessage());
        showState("STT ERROR • see report");
        cleanupSpeech(false);
        updateControls();
    }

    @Override public void onTimeout() {
        log("RECOGNITION_TIMEOUT after 20000ms");
        showState("Timeout • stop and try again");
        cleanupSpeech(false);
        updateControls();
    }

    private void stopRecognition(String why) {
        if(!listening) return;
        log("STOP_REQUESTED="+why+" elapsed_ms="+(SystemClock.elapsedRealtime()-sessionStart));
        if(speech!=null) {
            try { speech.stop(); } catch(Exception e) { log("STOP_EXCEPTION="+e); }
        }
        // Vosk posts its final result on main thread after stop; keep report and transcript.
        cleanupSpeech(false);
        showState("STOPPED • see final transcript and log");
        updateControls();
    }

    private void cleanupSpeech(boolean fromFinalCallback) {
        SpeechService old=speech;
        speech=null;
        listening=false;
        if(old!=null) {
            try {
                if(!fromFinalCallback) old.cancel();
                old.shutdown();
            } catch(Exception e) { log("CLEANUP_SPEECH_ERROR="+e); }
        }
        if(activeRecognizer!=null) {
            try { activeRecognizer.close(); }
            catch(Exception e) { log("CLEANUP_RECOGNIZER_ERROR="+e); }
            activeRecognizer=null;
        }
    }

    private void updateControls(){
        allow.setText(hasMic()?"MICROPHONE: ALLOWED ✓":"1) ALLOW MICROPHONE");
        start.setEnabled(model!=null && hasMic() && !listening);
        stop.setEnabled(listening);
    }

    private boolean hasMic(){
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
    }
    private void showState(String message){state.setText(message);}
    private void log(String message){
        String time=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date());
        report.append('[').append(time).append("] ").append(message).append('\n');
        if(details!=null) details.setText(report.toString());
        if(logScroll!=null) logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));
    }
    private Button button(String label){
        Button b=new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(44,105,193)));
        return b;
    }
    private TextView text(String label,int size,boolean bold,int color){
        TextView v=new TextView(this); v.setText(label); v.setTextSize(size); v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private View space(int height){
        View v=new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(height)));
        return v;
    }
    private int dp(int amount){return Math.round(amount*getResources().getDisplayMetrics().density);}

    @Override protected void onStop(){
        if(listening)stopRecognition("APP_BACKGROUND");
        super.onStop();
    }
    @Override protected void onDestroy(){
        alive=false;
        cleanupSpeech(false);
        if(model!=null){try{model.close();}catch(Exception ignored){} model=null;}
        super.onDestroy();
    }
}
