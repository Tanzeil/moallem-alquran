package com.tanzeil.moallemalquran;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;
    private QuranLabBridge quranLabBridge;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        quranLabBridge = new QuranLabBridge(this, webView);
        webView.addJavascriptInterface(quranLabBridge, "QuranLab");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onDestroy() {
        if (quranLabBridge != null) quranLabBridge.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
