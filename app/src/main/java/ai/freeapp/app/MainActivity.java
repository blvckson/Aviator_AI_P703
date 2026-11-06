package ai.freeapp.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import org.json.JSONObject;
import java.util.ArrayList;
import android.webkit.JavascriptInterface;
import java.util.Locale;
import android.content.pm.ApplicationInfo;
import java.util.List;
import android.Manifest;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 1001;
    private static final int FILE_CHOOSER_REQUEST = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private TextToSpeech textToSpeech;
    private volatile boolean ttsReady = false;
    private String pendingNarration = null;
    private int narrationBatch = 0;
    private volatile String lastUtteranceId = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textToSpeech = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
                ttsReady = false;
                notifyNarratorStatus("Text-to-Speech could not initialize. Check the Android TTS engine and language data.");
                return;
            }

            int languageResult = textToSpeech.setLanguage(Locale.US);
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                languageResult = textToSpeech.setLanguage(Locale.UK);
            }
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                languageResult = textToSpeech.setLanguage(Locale.getDefault());
            }
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA
                    && languageResult != TextToSpeech.LANG_NOT_SUPPORTED;

            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    notifyNarratorStatus("Reading the answer aloud. Tap Stop to cancel.");
                }
                @Override public void onDone(String utteranceId) {
                    if (utteranceId != null && utteranceId.equals(lastUtteranceId)) {
                        notifyNarratorStatus("Finished reading the answer.");
                    }
                }
                @Override public void onError(String utteranceId) {
                    notifyNarratorStatus("Narration failed during playback. Tap Read Latest Answer to try again.");
                }
                @Override public void onError(String utteranceId, int errorCode) {
                    onError(utteranceId);
                }
            });

            if (!ttsReady) {
                notifyNarratorStatus("No supported TTS language is ready. Install or enable English voice data in Android Text-to-Speech settings.");
                return;
            }
            notifyNarratorStatus("Narrator ready.");
            if (pendingNarration != null && !pendingNarration.trim().isEmpty()) {
                String queued = pendingNarration;
                pendingNarration = null;
                speakNarration(queued);
            }
        });

        webView = new WebView(this);
        webView.addJavascriptInterface(new NativeNarrator(), "FreeAppNativeVoice");
        webView.addJavascriptInterface(new NativePhoneControl(), "FreeAppNativePhone");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (request.getResources() == null) {
                        request.deny();
                        return;
                    }
                    boolean audioAllowed =
                            android.os.Build.VERSION.SDK_INT < 23 ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                    if (audioAllowed) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        request.deny();
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception ignored) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClientCompat() {
            private boolean rendererRecoveryScheduled = false;

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // Android 8+ may kill the renderer under memory pressure. Android requires
                // the dead WebView to be detached and destroyed before it is recreated.
                if (!rendererRecoveryScheduled) {
                    rendererRecoveryScheduled = true;
                    runOnUiThread(() -> {
                        try {
                            if (view != null) {
                                android.view.ViewParent parent = view.getParent();
                                if (parent instanceof android.view.ViewGroup) {
                                    ((android.view.ViewGroup) parent).removeView(view);
                                }
                                view.stopLoading();
                                view.destroy();
                                if (webView == view) webView = null;
                            }
                        } catch (Exception ignored) {
                            // Continue recovery even if a partially-dead renderer rejects cleanup.
                        }
                        if (!isFinishing() && android.os.Build.VERSION.SDK_INT >= 26) recreate();
                    });
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view,
                    WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_PERMISSION_REQUEST
            );
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        setContentView(webView);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    results = new Uri[]{uri};
                } else if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    private final class NativePhoneControl {
        @JavascriptInterface
        public boolean isSupported() { return android.os.Build.VERSION.SDK_INT >= 24; }

        @JavascriptInterface
        public boolean isEnabled() {
            String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT)) && enabled.toLowerCase(Locale.ROOT).contains("phonecontrolservice");
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        @JavascriptInterface
        public void openApp(String app) {
            if (app == null) return;
            String target = app.trim().toLowerCase(Locale.ROOT);
            String packageName;
            if (target.equals("gmail") || target.equals("email")) packageName = "com.google.android.gm";
            else if (target.equals("settings") || target.equals("phone settings")) packageName = "com.android.settings";
            else if (target.equals("chrome") || target.equals("browser")) packageName = "com.android.chrome";
            else if (target.equals("play store") || target.equals("playstore")) packageName = "com.android.vending";
            else if (target.equals("files") || target.equals("file manager")) packageName = "com.google.android.documentsui";
            else {
                packageName = findInstalledPackage(target);
                if (packageName == null) {
                    runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                            "No unique installed app matched \"" + app + "\". Use its exact app name.",
                            android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
            }
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch == null && target.equals("files")) launch = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*");
                if (launch == null) {
                    runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "That app is not installed on this phone.", android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Could not open that app.", android.widget.Toast.LENGTH_LONG).show());
            }
        }

        private String findInstalledPackage(String target) {
            if (target == null || target.trim().isEmpty()) return null;
            String wanted = target.trim().toLowerCase(Locale.ROOT);
            List<ApplicationInfo> apps = getPackageManager().getInstalledApplications(0);
            String partialMatch = null;
            for (ApplicationInfo info : apps) {
                CharSequence label;
                try { label = getPackageManager().getApplicationLabel(info); }
                catch (Exception ignored) { continue; }
                if (label == null) continue;
                String name = label.toString().trim().toLowerCase(Locale.ROOT);
                if (name.equals(wanted)) return info.packageName;
                if (name.contains(wanted) || wanted.contains(name)) {
                    if (partialMatch != null && !partialMatch.equals(info.packageName)) return null;
                    partialMatch = info.packageName;
                }
            }
            return partialMatch;
        }

        @JavascriptInterface
        public void stopPendingActions() {
            getSharedPreferences("freeapp_phone_control", MODE_PRIVATE).edit()
                    .remove("pending_action").remove("pending_value").apply();
        }

        @JavascriptInterface
        public void runAction(String action, String value) {
            if (action == null) return;
            String a = action.trim().toLowerCase(Locale.ROOT);
            if (!(a.equals("tap") || a.equals("type") || a.equals("scroll_down") || a.equals("scroll_up") || a.equals("back") || a.equals("home"))) return;
            if (a.equals("type") && value != null) {
                String v = value.toLowerCase(Locale.ROOT);
                if (v.contains("password") || v.contains("passcode") || v.contains("otp") || v.contains("verification code") || v.contains("security code") || v.contains("cvv")) {
                    runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "For your security, enter passwords and verification codes yourself.", android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
            }
            PhoneControlService.queueAction(MainActivity.this, a, value);
        }

        @JavascriptInterface
        public void openSystemUpdates() {
            runOnUiThread(() -> {
                try { startActivity(new Intent("android.settings.SYSTEM_UPDATE_SETTINGS")); }
                catch (Exception ignored) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
            });
        }
    }

    private final class NativeNarrator {
        @JavascriptInterface
        public void speak(String text) {
            if (text == null || text.trim().isEmpty()) {
                notifyNarratorStatus("There is no answer text to read yet.");
                return;
            }
            String safeText = text.trim();
            runOnUiThread(() -> {
                if (textToSpeech == null || !ttsReady) {
                    pendingNarration = safeText;
                    notifyNarratorStatus("Preparing the device narrator. Playback will begin when TTS is ready.");
                    return;
                }
                speakNarration(safeText);
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                pendingNarration = null;
                lastUtteranceId = null;
                if (textToSpeech != null) textToSpeech.stop();
                notifyNarratorStatus("Narration stopped.");
            });
        }
    }

    private void speakNarration(String input) {
        if (textToSpeech == null || !ttsReady || input == null || input.trim().isEmpty()) {
            notifyNarratorStatus("Narrator is not ready. Check Android Text-to-Speech settings and try again.");
            return;
        }
        String normalized = input.replaceAll("\\s+", " ").trim();
        final int maxChunk = Math.min(2800, TextToSpeech.getMaxSpeechInputLength() - 100);
        ArrayList<String> chunks = new ArrayList<>();
        while (normalized.length() > maxChunk) {
            int cut = normalized.lastIndexOf(' ', maxChunk);
            if (cut < maxChunk / 2) cut = maxChunk;
            chunks.add(normalized.substring(0, cut).trim());
            normalized = normalized.substring(cut).trim();
        }
        if (!normalized.isEmpty()) chunks.add(normalized);
        if (chunks.isEmpty()) return;

        narrationBatch++;
        int batch = narrationBatch;
        lastUtteranceId = "freeapp-narrator-" + batch + "-" + (chunks.size() - 1);
        textToSpeech.setSpeechRate(1.0f);
        textToSpeech.setPitch(0.98f);
        for (int i = 0; i < chunks.size(); i++) {
            String id = "freeapp-narrator-" + batch + "-" + i;
            int queueMode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = textToSpeech.speak(chunks.get(i), queueMode, null, id);
            if (result == TextToSpeech.ERROR) {
                notifyNarratorStatus("Android TTS rejected part of the answer. Try again or check installed voice data.");
                return;
            }
        }
        notifyNarratorStatus("Reading the answer aloud. Tap Stop to cancel.");
    }

    private void notifyNarratorStatus(String message) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String quoted = JSONObject.quote(message == null ? "" : message);
            webView.evaluateJavascript("window.FreeAppNarratorStatus && window.FreeAppNarratorStatus(" + quoted + ");", null);
        });
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroy();
    }
}
