package com.tanzeil.moallemalquran;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;
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
    private boolean pendingRecitationStart = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setPadding(0, 0, 0, dp(76));
        webView.setClipToPadding(false);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Native Android recitation control: bypasses the HTML button/event path.
        Button nativeRecitationButton = new Button(this);
        nativeRecitationButton.setText("🎙 بدء التسميع");
        nativeRecitationButton.setTextSize(16);
        nativeRecitationButton.setAllCaps(false);
        nativeRecitationButton.setContentDescription("بدء ميزة التسميع من أندرويد");
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        buttonParams.setMargins(dp(12), 0, dp(12), dp(8));
        root.addView(nativeRecitationButton, buttonParams);
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean audioRequested = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            audioRequested = true;
                            break;
                        }
                    }
                    if (!audioRequested) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
                    }
                });
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }
        });

        quranLabBridge = new QuranLabBridge(this, webView);
        webView.addJavascriptInterface(quranLabBridge, "QuranLab");
        nativeRecitationButton.setOnClickListener(v -> {
            Toast.makeText(this, "زر التسميع في أندرويد استجاب.", Toast.LENGTH_SHORT).show();
            if (quranLabBridge != null) quranLabBridge.startListening();
        });
        webView.loadUrl("file:///android_asset/index.html");
        // Start model preparation early so the first tap does not appear idle while downloading.
        quranLabBridge.prepareModel();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Ask for microphone access only when the user starts recitation.
    public void requestAudioPermissionForRecitation() {
        runOnUiThread(() -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (quranLabBridge != null) quranLabBridge.startListening();
                return;
            }
            pendingRecitationStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingWebPermission != null) {
                if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
                else pendingWebPermission.deny();
                pendingWebPermission = null;
            }
            if (pendingRecitationStart) {
                pendingRecitationStart = false;
                if (granted && quranLabBridge != null) {
                    quranLabBridge.startListening();
                } else if (webView != null) {
                    webView.evaluateJavascript("window.onQuranLabStatus && window.onQuranLabStatus('تم رفض صلاحية الميكروفون. اسمحي للتطبيق باستخدام الميكروفون ثم أعيدي المحاولة.')", null);
                }
            }
        }
    }

    @Override protected void onDestroy() {
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }
        if (quranLabBridge != null) quranLabBridge.close();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
