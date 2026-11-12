package ai.freeapp.app;

import android.app.Activity;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ValueCallback;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1002;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean fileChooserMultiple = false;
    private TextToSpeech textToSpeech;
    private volatile boolean ttsReady = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        // WebView needs content URI access for user-selected audio files in the voice-clone picker.
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int language = textToSpeech.setLanguage(Locale.getDefault());
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.US);
                }
            }
        });
        web.addJavascriptInterface(new NativeNarrator(), "AskMeNativeTTS");

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (android.os.Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else request.deny();
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                String[] acceptedTypes = fileChooserParams.getAcceptTypes();
                if (acceptedTypes == null || acceptedTypes.length == 0) {
                    intent.setType("*/*");
                } else if (acceptedTypes.length == 1 && acceptedTypes[0] != null && acceptedTypes[0].contains("/")) {
                    intent.setType(acceptedTypes[0]);
                } else {
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptedTypes);
                }
                fileChooserMultiple = fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserMultiple);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select file(s)"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (android.content.ActivityNotFoundException e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });

        web.setWebViewClient(new WebViewClientCompat() {
            private boolean isLocalAppAsset(Uri uri) {
                return uri != null && "https".equalsIgnoreCase(uri.getScheme()) &&
                        "appassets.androidplatform.net".equalsIgnoreCase(uri.getHost()) &&
                        uri.getPath() != null && uri.getPath().startsWith("/assets/");
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalAppAsset(uri)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (android.content.ActivityNotFoundException ignored) { }
                return true;
            }

            @Override @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = android.net.Uri.parse(url);
                if (isLocalAppAsset(uri)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (android.content.ActivityNotFoundException ignored) { }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(android.net.Uri.parse(url));
            }
        });

        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        setContentView(web);
    }

    public final class NativeNarrator {
        @JavascriptInterface
        public boolean speakChunk(String text, float rate, boolean append) {
            if (!ttsReady || textToSpeech == null || text == null || text.isEmpty()) return false;
            float safeRate = Math.max(0.30f, Math.min(1.50f, rate));
            textToSpeech.setSpeechRate(safeRate);
            int queueMode = append ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
            return textToSpeech.speak(text, queueMode, new Bundle(), "askme-" + System.nanoTime()) == TextToSpeech.SUCCESS;
        }

        @JavascriptInterface
        public void stop() {
            if (textToSpeech != null) textToSpeech.stop();
        }

        @JavascriptInterface
        public boolean isReady() {
            return ttsReady;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (fileChooserMultiple && data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
                fileChooserMultiple = false;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroy();
    }
}