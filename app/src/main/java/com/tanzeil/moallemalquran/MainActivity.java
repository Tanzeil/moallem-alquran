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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private WebView webView;
    private PermissionRequest pendingRequest;
    private static final int MIC_REQUEST = 41;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsAudio = false;
                    for (String resource : request.getResources())
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                    if (wantsAudio && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
                    } else request.grant(request.getResources());
                });
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_REQUEST && pendingRequest != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingRequest.grant(pendingRequest.getResources());
            else pendingRequest.deny();
            pendingRequest = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
