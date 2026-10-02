package vn.softworld.readingrobot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Reading Robot Android shell.
 *
 * Loads the web app bundled in assets/ and gives it native speech:
 *   window.RRNative.startListening(lang) / stopListening()   -> android.speech.SpeechRecognizer
 *   window.RRNative.speak(text, id, locale, rate, pitch)       -> android.speech.tts.TextToSpeech
 *   window.RRNative.share(title, text) / saveFile(name, text, type)
 * Results come back through window.RRSpeech.onResult(text, isFinal) and onSpeakDone(id).
 *
 * The recogniser is paused while the robot speaks so its own voice is never scored.
 */
public class MainActivity extends Activity {
    private static final int REQ_MIC = 7;

    private WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean wantListening = false;   // JS asked us to listen
    private boolean speaking = false;        // TTS is talking: recogniser paused
    private String listenLang = "en-AU";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // reports, roster and passages live in localStorage
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);                           // the robot screen scales itself
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "RRNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setLanguage(new Locale("en", "AU"));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> speakFinished(id)); }
            @Override public void onError(String id) { main.post(() -> speakFinished(id)); }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Reading Robot needs the microphone to listen to reading.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ JS -> native
    private class Bridge {
        @JavascriptInterface public void startListening(String lang) {
            main.post(() -> { listenLang = lang == null || lang.isEmpty() ? "en-AU" : lang; wantListening = true; if (!speaking) beginRecognition(); });
        }
        @JavascriptInterface public void stopListening() {
            main.post(() -> { wantListening = false; endRecognition(); });
        }
        @JavascriptInterface public void speak(String text, String id, String locale, double rate, double pitch) {
            main.post(() -> doSpeak(text, id, locale, (float) rate, (float) pitch));
        }
        @JavascriptInterface public void stopSpeaking() { main.post(() -> { if (tts != null) tts.stop(); }); }
        @JavascriptInterface public void share(String title, String text) { main.post(() -> shareText(title, text, "text/plain")); }
        @JavascriptInterface public void saveFile(String name, String text, String type) { main.post(() -> shareText(name, text, type)); }
    }

    // --------------------------------------------------------------- listening
    private void beginRecognition() {
        if (!wantListening || speaking) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            js("window.RRSpeech.onError && window.RRSpeech.onError('mic-denied')");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.RRSpeech.onError && window.RRSpeech.onError('no-recognizer')");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // children pause mid-sentence: ask for longer silence before the utterance ends
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        muteBeep(true);
        try { recognizer.startListening(i); } catch (Exception e) { restartSoon(400); }
    }

    private void endRecognition() {
        if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) { } }
        muteBeep(false);
    }

    private void restartSoon(long ms) {
        main.postDelayed(() -> { if (wantListening && !speaking) beginRecognition(); }, ms);
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override public void onPartialResults(Bundle b) { deliver(b, false); }

        @Override public void onResults(Bundle b) {
            deliver(b, true);
            restartSoon(50);           // keep listening for the next sentence
        }

        @Override public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                js("window.RRSpeech.onError && window.RRSpeech.onError('mic-denied')");
                return;
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) { endRecognition(); restartSoon(600); return; }
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT happen whenever the child pauses: just listen again
            restartSoon(error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_SERVER ? 1500 : 150);
        }
    }

    private void deliver(Bundle b, boolean isFinal) {
        if (speaking) return;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        String text = list.get(0);
        if (text == null || text.trim().isEmpty()) return;
        js("window.RRSpeech.onResult && window.RRSpeech.onResult(" + JSONObject.quote(text) + "," + isFinal + ")");
    }

    /** Silence the recogniser's start/stop beep (it plays on the notification stream). */
    private void muteBeep(boolean mute) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION,
                        mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (Exception ignored) { }   // Do Not Disturb may forbid it; the beep is harmless
    }

    // ---------------------------------------------------------------- speaking
    private void doSpeak(String text, String id, String locale, float rate, float pitch) {
        if (tts == null || !ttsReady) { js("window.RRSpeech.onSpeakDone && window.RRSpeech.onSpeakDone(" + JSONObject.quote(id) + ")"); return; }
        speaking = true;
        endRecognition();                                  // don't listen to ourselves
        Locale loc = Locale.forLanguageTag(locale == null || locale.isEmpty() ? "en-AU" : locale);
        if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(loc);
        tts.setSpeechRate(rate > 0 ? rate : 0.95f);
        tts.setPitch(pitch > 0 ? pitch : 1.15f);
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
    }

    private void speakFinished(String id) {
        speaking = false;
        js("window.RRSpeech.onSpeakDone && window.RRSpeech.onSpeakDone(" + JSONObject.quote(id) + ")");
        restartSoon(350);                                  // resume listening after the echo dies down
    }

    // ------------------------------------------------------------------ helpers
    private void shareText(String title, String text, String type) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, title));
    }

    private void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    @Override
    public void onBackPressed() {
        // Kiosk: back goes to the home screen of the app instead of closing it.
        js("window.goHome && goHome()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        endRecognition();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wantListening) restartSoon(300);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
