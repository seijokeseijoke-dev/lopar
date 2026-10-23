package se.lopar.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private boolean pendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });
        webView.addJavascriptInterface(new Bridge(), "Run");

        setContentView(webView);
        askPermissions();
        webView.loadUrl(START_URL);
    }

    /** Metoder som sidan anropar via window.Run */
    private class Bridge {
        @JavascriptInterface
        public void start() {
            runOnUiThread(() -> {
                if (hasLocation()) {
                    startRun();
                } else {
                    pendingStart = true;
                    askPermissions();
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> {
                if (RunService.running) send(RunService.ACTION_STOP);
            });
        }

        @JavascriptInterface
        public void clear() {
            runOnUiThread(() -> {
                if (RunService.running) send(RunService.ACTION_CLEAR);
                else RunService.resetIdle();
            });
        }

        @JavascriptInterface
        public void close() {
            runOnUiThread(() -> {
                // Avsluta passet, ta bort notisen och stäng appen helt.
                stopService(new Intent(MainActivity.this, RunService.class));
                RunService.resetIdle();
                finishAndRemoveTask();
            });
        }

        @JavascriptInterface
        public String state() {
            String st = RunService.status.replace("\\", "\\\\").replace("\"", "\\\"");
            return "{\"running\":" + RunService.running
                    + ",\"ms\":" + RunService.elapsedMs()
                    + ",\"m\":" + RunService.meters
                    + ",\"status\":\"" + st + "\"}";
        }
    }

    private void startRun() {
        Intent i = new Intent(this, RunService.class).setAction(RunService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void send(String action) {
        startService(new Intent(this, RunService.class).setAction(action));
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasLocation()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (pendingStart) {
            pendingStart = false;
            if (hasLocation()) startRun();
            else RunService.status = "Platsåtkomst nekad – tillåt Plats i appens inställningar";
        }
    }

    @Override
    public void onBackPressed() {
        // Stäng inte appen av misstag mitt i ett pass.
        moveTaskToBack(true);
    }
}
