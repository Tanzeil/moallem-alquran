package com.tanzeil.moallemalquran;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 2001;

    private WebView webView;
    private QuranLabBridge quranLabBridge;
    private PermissionRequest pendingWebPermission;
    private boolean pendingRecitationStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audioRequested = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            audioRequested = true;
                        } else {
                            request.deny();
                            return;
                        }
                    }
                    if (!audioRequested) {
                        request.deny();
                        return;
                    }

                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingWebPermission = request;
                        requestAudioPermission(false);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) {
                    pendingWebPermission = null;
                }
            }
        });

        quranLabBridge = new QuranLabBridge(this, webView);
        webView.addJavascriptInterface(quranLabBridge, "QuranLab");
        webView.loadUrl("file:///android_asset/index.html");

        // Prepare the model in the background; the recitation button remains responsive.
        quranLabBridge.prepareModel();
    }

    /**
     * Called by the bridge only after the user taps the recitation button.
     * Android requires an explicit runtime microphone permission.
     */
    public void requestAudioPermissionForRecitation() {
        runOnUiThread(() -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (quranLabBridge != null) quranLabBridge.startListening();
            } else {
                requestAudioPermission(true);
            }
        });
    }

    private void requestAudioPermission(boolean startRecitationAfterGrant) {
        pendingRecitationStart = startRecitationAfterGrant;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                AUDIO_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (pendingWebPermission != null) {
            if (granted) {
                pendingWebPermission.grant(pendingWebPermission.getResources());
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
        }

        if (pendingRecitationStart) {
            pendingRecitationStart = false;
            if (granted && quranLabBridge != null) {
                quranLabBridge.startListening();
            } else {
                sendStatus("لم تسمحي باستخدام الميكروفون. افتحي إعدادات التطبيق وفعّلي إذن الميكروفون ثم أعيدي المحاولة.");
            }
        }
    }

    private void sendStatus(String message) {
        if (webView == null) return;
        String quoted = org.json.JSONObject.quote(message);
        webView.post(() -> webView.evaluateJavascript(
                "window.onQuranLabStatus && window.onQuranLabStatus(" + quoted + ")", null));
    }

    @Override
    protected void onDestroy() {
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }
        if (quranLabBridge != null) quranLabBridge.close();
        if (webView != null) {
            webView.removeJavascriptInterface("QuranLab");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
