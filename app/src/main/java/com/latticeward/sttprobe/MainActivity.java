package com.latticeward.sttprobe;

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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Isolated, permission-gated ON-DEVICE STT diagnostic. No Media3, TTS, backend,
 * automatic capture, audio persistence, or generic/cloud SpeechRecognizer.
 */
public final class MainActivity extends Activity {
    private static final int MIC_REQUEST = 701;
    private static final String[] LANGUAGE_TAGS = {"en-US", "fa-IR"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private TextView status, transcript, diagnostics;
    private ScrollView reportScroll;
    private Spinner languageSpinner;
    private Button permissionButton, startButton, systemButton, downloadButton, stopButton;
    private SpeechRecognizer modelDownloader;
    private SpeechRecognizer recognizer;
    private int generation = 0;
    private boolean startIssued;
    private long startedAtMs;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10, 15, 28));
        getWindow().setNavigationBarColor(Color.rgb(10, 15, 28));
        createUi();
        log("APP_STARTED: Voice Probe V2 with system/default and offline recognition; no app Internet permission.");
        log("SDK_INT=" + Build.VERSION.SDK_INT +
                "; Android=" + Build.VERSION.RELEASE + "; locale=" + Locale.getDefault().toLanguageTag());
        refreshCapabilities();
    }

    private void createUi() {
        int bg = Color.rgb(10, 15, 28), card = Color.rgb(23, 32, 52);
        int text = Color.rgb(234, 240, 250), muted = Color.rgb(162, 178, 205);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(18));
        root.setBackgroundColor(bg);
        setContentView(root);

        TextView heading = label("Voice Probe V2", 25, text, true);
        root.addView(heading);
        root.addView(label("تست مستقل تشخیص گفتار محلی؛ بدون ارتباط با Music Copilot", 13, muted, false));
        root.addView(space(12));

        status = label("آماده", 16, Color.rgb(146, 219, 180), true);
        status.setPadding(dp(10), dp(12), dp(10), dp(12));
        status.setBackgroundColor(card);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(space(12));

        root.addView(label("زبان تشخیص (Issue #10: English)", 13, muted, false));
        languageSpinner = new Spinner(this);
        String[] items = {"English (en-US)", "Persian (fa-IR)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, items);
        languageSpinner.setAdapter(adapter);
        languageSpinner.setBackgroundColor(Color.rgb(37, 51, 78));
        root.addView(languageSpinner, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(space(10));

        permissionButton = button("۱. درخواست مجوز میکروفون");
        startButton = button("2) TEST ON-DEVICE ONLY STT");
        systemButton = button("1) TEST SYSTEM DEFAULT STT");
        downloadButton = button("3) REQUEST OFFLINE MODEL DOWNLOAD");
        stopButton = button("Stop / توقف");
        root.addView(permissionButton);
        root.addView(space(7));
        root.addView(systemButton);
        root.addView(space(7));
        root.addView(startButton);
        root.addView(space(7));
        root.addView(downloadButton);
        root.addView(space(7));
        root.addView(stopButton);
        stopButton.setEnabled(false);
        permissionButton.setOnClickListener(v -> requestMic());
        startButton.setOnClickListener(v -> startListening(true));
        systemButton.setOnClickListener(v -> startListening(false));
        downloadButton.setOnClickListener(v -> requestModelDownload());
        stopButton.setOnClickListener(v -> abort("USER_STOPPED"));

        root.addView(space(10));
        root.addView(label("متن تشخیص داده‌شده", 14, muted, true));
        transcript = label("هنوز گفتاری دریافت نشده است.", 17, text, false);
        transcript.setPadding(dp(12), dp(12), dp(12), dp(12));
        transcript.setBackgroundColor(card);
        transcript.setTextIsSelectable(true);
        root.addView(transcript, new LinearLayout.LayoutParams(-1, -2));
        root.addView(space(10));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("Copy report");
        Button clear = button("Clear");
        tools.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(space(8));
        tools.addView(clear, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(tools);
        copy.setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("Music STT Probe report", report.toString()));
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
        });
        clear.setOnClickListener(v -> {
            if (recognizer != null) abort("CLEAR_CANCELLED_ACTIVE_SESSION");
            report.setLength(0);
            diagnostics.setText("");
            transcript.setText("هنوز گفتاری دریافت نشده است.");
            log("REPORT_CLEARED; no persistent file saved");
        });

        root.addView(space(8));
        root.addView(label("جزئیات فنی / Diagnostic log", 14, muted, true));
        reportScroll = new ScrollView(this);
        diagnostics = label("", 12, text, false);
        diagnostics.setTextIsSelectable(true);
        diagnostics.setTypeface(Typeface.MONOSPACE);
        diagnostics.setPadding(dp(12), dp(12), dp(12), dp(14));
        diagnostics.setBackgroundColor(card);
        reportScroll.addView(diagnostics);
        root.addView(reportScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(label("فقط پس از لمس Start ضبط می‌کند؛ متن و گزارش فقط در حافظه‌اند.",
                11, muted, false));
    }

    private void refreshCapabilities() {
        boolean permission = micGranted();
        downloadButton.setEnabled(Build.VERSION.SDK_INT >= 33);
        try { systemButton.setEnabled(SpeechRecognizer.isRecognitionAvailable(this)); }
        catch (Exception e) { log("DEFAULT_AVAILABILITY_ERROR=" + detail(e)); systemButton.setEnabled(true); }
        permissionButton.setText(permission ? "مجوز میکروفون: Allow ✓" :
                "۱. درخواست مجوز میکروفون");
        log("RECORD_AUDIO=" + (permission ? "GRANTED" : "DENIED"));
        if (Build.VERSION.SDK_INT < 31) {
            status("Android 10/11: on-device STT API not available");
            log("UNSUPPORTED_PLATFORM: createOnDeviceSpeechRecognizer requires Android 12/API31.");
            startButton.setEnabled(false);
            return;
        }
        try {
            boolean installed = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
            log("isOnDeviceRecognitionAvailable=" + installed);
            startButton.setEnabled(true);
            status(installed ? "موتور محلی شناسایی شد؛ برای تست Start را بزن" :
                    "موتور STT محلی شناسایی نشد");
        } catch (Exception e) {
            log("CAPABILITY_QUERY_EXCEPTION=" + detail(e));
            status("خطا در بررسی سرویس STT");
            startButton.setEnabled(true);
        }
    }

    private void requestMic() {
        if (micGranted()) {
            log("PERMISSION_ALREADY_GRANTED: no Android dialog necessary");
            refreshCapabilities();
            return;
        }
        log("PERMISSION_DIALOG_REQUESTED (user action)");
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MIC_REQUEST) return;
        log("PERMISSION_RESULT: " + (micGranted() ? "GRANTED" : "DENIED") +
                "; new Start tap REQUIRED; no auto-capture");
        refreshCapabilities();
    }

    private void startListening(boolean offlineOnly) {
        log("BUTTON_MODE=" + (offlineOnly ? "ON_DEVICE_ONLY" : "SYSTEM_DEFAULT"));
        if (recognizer != null) {
            log("IGNORED_SECOND_START: active session already owns recognizer");
            return;
        }
        if (!micGranted()) {
            status("ابتدا مجوز میکروفون را بده");
            log("START_REJECTED: RECORD_AUDIO permission denied. No recording started.");
            requestMic();
            return;
        }
        if (offlineOnly && Build.VERSION.SDK_INT < 31) {
            log("START_REJECTED: on-device API requires 31+.");
            return;
        }
        final boolean available;
        try {
            available = offlineOnly ? SpeechRecognizer.isOnDeviceRecognitionAvailable(this) : SpeechRecognizer.isRecognitionAvailable(this);
        } catch (Exception e) {
            status("خطا در بررسی موتور محلی");
            log("START_AVAILABILITY_EXCEPTION=" + detail(e));
            return;
        }
        if (!available) {
            status("موتور STT محلی وجود ندارد");
            log("START_REJECTED: provider unavailable mode=" + (offlineOnly ? "ON_DEVICE_ONLY" : "SYSTEM_DEFAULT"));
            return;
        }

        final int token = ++generation;
        startedAtMs = SystemClock.elapsedRealtime();
        startIssued = false;
        String languageTag = LANGUAGE_TAGS[languageSpinner.getSelectedItemPosition()];
        log("SESSION_" + token + "_START: language=" + languageTag + "; provider=" + (offlineOnly ? "ON_DEVICE_ONLY" : "SYSTEM_DEFAULT"));
        transcript.setText("در انتظار گفتار...");
        try {
            closeDownloader();
            recognizer = offlineOnly ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this) : SpeechRecognizer.createSpeechRecognizer(this);
            log("CREATE_RECOGNIZER_SUCCESS mode=" + (offlineOnly ? "ON_DEVICE_ONLY" : "SYSTEM_DEFAULT"));
        } catch (Exception e) {
            log("CREATE_ON_DEVICE_RECOGNIZER=FAILED " + detail(e));
            status("خطا در ساخت موتور تشخیص گفتار");
            return;
        }
        startButton.setEnabled(false);
        systemButton.setEnabled(false);
        stopButton.setEnabled(true);
        status("در حال بررسی پشتیبانی زبان محلی...");

        final Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag);
        request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineOnly);
        request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle args) {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    log("CALLBACK_ON_READY_FOR_SPEECH elapsed_ms=" + elapsed());
                    status("Listening ✓ — حالا صحبت کن");
                });
            }
            @Override public void onBeginningOfSpeech() {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    log("CALLBACK_ON_BEGINNING_OF_SPEECH elapsed_ms=" + elapsed());
                    status("در حال شنیدن صدای شما...");
                });
            }
            @Override public void onRmsChanged(float rms) { /* No audio samples or log spam. */ }
            @Override public void onBufferReceived(byte[] bytes) { /* Do not retain audio. */ }
            @Override public void onEndOfSpeech() {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    log("CALLBACK_ON_END_OF_SPEECH elapsed_ms=" + elapsed());
                    status("در حال پردازش گفتار...");
                });
            }
            @Override public void onError(int error) {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    log("RECOGNITION_FAILED code=" + error + "; name=" +
                            errorName(error) + "; elapsed_ms=" + elapsed());
                    status("خطای STT: " + errorName(error) + " (" + error + ")");
                    transcript.setText("خطا؛ جزئیات در گزارش پایین نمایش داده شده است.");
                    finish(token, true);
                });
            }
            @Override public void onResults(Bundle results) {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    ArrayList<String> matches = results == null ? null :
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    float[] scores = results == null ? null :
                            results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches == null || matches.isEmpty()) {
                        log("RECOGNITION_EMPTY_RESULT elapsed_ms=" + elapsed());
                        transcript.setText("(empty)");
                        status("موتور پاسخ خالی برگرداند");
                    } else {
                        transcript.setText(matches.get(0));
                        log("RECOGNITION_SUCCESS elapsed_ms=" + elapsed() +
                                "; transcript_length=" + matches.get(0).length() +
                                "; alternatives=" + matches.size());
                        for (int i = 0; i < matches.size(); i++) {
                            String confidence = scores != null && i < scores.length ?
                                    String.valueOf(scores[i]) : "NOT_PROVIDED";
                            log("RESULT_" + (i + 1) + ": " + matches.get(i) +
                                    "; confidence=" + confidence);
                        }
                        status("موفق ✓ متن گفتار ثبت شد");
                    }
                    finish(token, false);
                });
            }
            @Override public void onPartialResults(Bundle partial) {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    ArrayList<String> words = partial == null ? null :
                            partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (words != null && !words.isEmpty()) {
                        transcript.setText(words.get(0) + " …");
                    }
                });
            }
            @Override public void onEvent(int type, Bundle args) {
                runOnUiThread(() -> { if (current(token)) log("CALLBACK_EVENT type=" + type); });
            }
        });

        // Diagnostic metadata check must not turn an available offline service
        // into a premature failure. Use a bounded fallback if metadata hangs.
        handler.postDelayed(() -> {
            if (current(token) && !startIssued) {
                log("SUPPORT_QUERY_TIMEOUT_2500MS: attempting strictly on-device start");
                issueStart(token, request);
            }
        }, 2500);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                recognizer.checkRecognitionSupport(request, getMainExecutor(),
                        new RecognitionSupportCallback() {
                    @Override public void onSupportResult(RecognitionSupport support) {
                        if (!current(token) || startIssued) return;
                        log("SUPPORT_QUERY_SUCCESS elapsed_ms=" + elapsed());
                        log("LANGUAGES_INSTALLED_ON_DEVICE=" +
                                support.getInstalledOnDeviceLanguages());
                        log("LANGUAGES_SUPPORTED_DOWNLOADABLE=" +
                                support.getSupportedOnDeviceLanguages());
                        log("LANGUAGES_PENDING=" + support.getPendingOnDeviceLanguages());
                        issueStart(token, request);
                    }
                    @Override public void onError(int error) {
                        if (!current(token) || startIssued) return;
                        log("SUPPORT_QUERY_ERROR code=" + error + "; name=" +
                                errorName(error) + ". Still trying requested mode.");
                        issueStart(token, request);
                    }
                });
            } catch (Exception e) {
                log("SUPPORT_QUERY_EXCEPTION=" + detail(e));
                issueStart(token, request);
            }
        } else {
            log("NO_SUPPORT_QUERY_API: Android 12 supports on-device start only");
            issueStart(token, request);
        }
        handler.postDelayed(() -> {
            if (current(token)) {
                log("SESSION_TIMEOUT_16000MS: no terminal recognition callback; cancelling.");
                status("Timeout — موتور پاسخ نداد");
                finish(token, true);
            }
        }, 16000);
    }

    private void issueStart(int token, Intent request) {
        if (!current(token) || startIssued) return;
        startIssued = true;
        log("START_LISTENING_CALLED elapsed_ms=" + elapsed());
        try {
            recognizer.startListening(request);
            status("شروع Listening درخواست شد؛ منتظر پاسخ موتور...");
        } catch (Exception e) {
            log("START_LISTENING_EXCEPTION=" + detail(e));
            status("startListening failed");
            finish(token, true);
        }
    }

    private void requestModelDownload() {
        if (Build.VERSION.SDK_INT < 33) { log("MODEL_DOWNLOAD_UNSUPPORTED: needs API33+"); return; }
        if (recognizer != null) { log("MODEL_DOWNLOAD_REJECTED: stop STT session first"); return; }
        try {
            closeDownloader();
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                log("MODEL_DOWNLOAD_REJECTED: no on-device recognition service"); return;
            }
            modelDownloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String tag = LANGUAGE_TAGS[languageSpinner.getSelectedItemPosition()];
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
            log("MODEL_DOWNLOAD_REQUESTED language=" + tag + "; provider=ON_DEVICE_ONLY");
            status("درخواست دانلود مدل " + tag);
            if (Build.VERSION.SDK_INT >= 34) {
                modelDownloader.triggerModelDownload(intent, getMainExecutor(), new android.speech.ModelDownloadListener() {
                    @Override public void onProgress(int progress) { log("MODEL_DOWNLOAD_PROGRESS=" + progress); }
                    @Override public void onSuccess() { log("MODEL_DOWNLOAD_SUCCESS"); status("مدل آماده شد؛ Offline را تست کن"); closeDownloader(); }
                    @Override public void onScheduled() { log("MODEL_DOWNLOAD_SCHEDULED"); status("دانلود زمان‌بندی شد"); closeDownloader(); }
                    @Override public void onError(int code) { log("MODEL_DOWNLOAD_ERROR=" + code + " " + errorName(code)); status("خطای دانلود مدل: " + code); closeDownloader(); }
                });
            } else {
                modelDownloader.triggerModelDownload(intent);
                log("MODEL_DOWNLOAD_TRIGGERED; API33 has no callback for progress");
            }
        } catch (Exception e) {
            log("MODEL_DOWNLOAD_EXCEPTION " + detail(e));
            closeDownloader();
        }
    }

    private void closeDownloader() {
        if (modelDownloader != null) {
            SpeechRecognizer old = modelDownloader;
            modelDownloader = null;
            try { old.destroy(); } catch (Exception e) { log("MODEL_DOWNLOAD_CLEANUP=" + detail(e)); }
        }
    }

    private void abort(String why) {
        if (recognizer == null) return;
        log("SESSION_CANCELLED reason=" + why + "; elapsed_ms=" + elapsed());
        status("ضبط لغو شد");
        finish(generation, true);
    }

    private void finish(int token, boolean cancelFirst) {
        if (!current(token)) return;
        SpeechRecognizer old = recognizer;
        recognizer = null;
        ++generation; // Reject all stale provider/watchdog callbacks.
        try { if (cancelFirst) old.cancel(); }
        catch (Exception e) { log("CANCEL_EXCEPTION=" + detail(e)); }
        try { old.destroy(); }
        catch (Exception e) { log("DESTROY_EXCEPTION=" + detail(e)); }
        startButton.setEnabled(true);
        systemButton.setEnabled(true);
        stopButton.setEnabled(false);
        log("SESSION_CLOSED; audio/transcript not saved to storage");
    }

    private boolean current(int token) { return recognizer != null && generation == token; }
    private long elapsed() { return SystemClock.elapsedRealtime() - startedAtMs; }
    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED;
    }
    private void status(String message) { status.setText(message); }
    private void log(String line) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        report.append('[').append(time).append("] ").append(line).append('\n');
        diagnostics.setText(report.toString());
        reportScroll.post(() -> reportScroll.fullScroll(View.FOCUS_DOWN));
    }
    private static String detail(Exception error) {
        return error.getClass().getSimpleName() +
                (error.getMessage() == null ? "" : ": " + error.getMessage());
    }
    private static String errorName(int code) {
        switch (code) {
            case 1: return "NETWORK_TIMEOUT";
            case 2: return "NETWORK";
            case 3: return "AUDIO";
            case 4: return "SERVER";
            case 5: return "CLIENT";
            case 6: return "SPEECH_TIMEOUT";
            case 7: return "NO_MATCH";
            case 8: return "RECOGNIZER_BUSY";
            case 9: return "INSUFFICIENT_PERMISSIONS";
            case 10: return "TOO_MANY_REQUESTS";
            case 11: return "SERVER_DISCONNECTED";
            case 12: return "LANGUAGE_NOT_SUPPORTED";
            case 13: return "LANGUAGE_UNAVAILABLE";
            case 14: return "CANNOT_CHECK_SUPPORT";
            case 15: return "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS";
            default: return "UNKNOWN_ERROR_" + code;
        }
    }
    private Button button(String title) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(43, 103, 190)));
        button.setMinHeight(dp(44));
        return button;
    }
    private TextView label(String title, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private View space(int dp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), dp(dp)));
        return view;
    }
    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStop() {
        if (recognizer != null) abort("APP_LEFT_FOREGROUND");
        super.onStop();
    }

    @Override protected void onDestroy() {
        closeDownloader();
        if (recognizer != null) abort("ACTIVITY_DESTROYED");
        super.onDestroy();
    }
}
