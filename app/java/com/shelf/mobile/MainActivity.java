package com.shelf.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Native shell for the Shelf web app.
 *
 * The HTML/CSS/JS lives in assets/www and is served from a private, synthetic
 * HTTPS origin (https://shelf.appassets.local/) instead of file://. That gives
 * the page a real secure origin: localStorage works, CORS requests carry a
 * normal Origin header, and YouTube iframe embeds get a valid Referer. No
 * network request ever leaves the device for that host -- AssetWebViewClient
 * answers every request straight from the APK.
 */
public final class MainActivity extends Activity {

    private static final String BASE_HOST = "shelf.appassets.local";
    private static final String BASE_URL = "https://" + BASE_HOST + "/";

    /** A plain Chrome UA (no "; wv" marker) keeps YouTube's iframe player happy. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Handles overlays that do not own a history entry (sync sheet, etc.). */
    private static final String BACK_JS = "(function(){"
            + "function hit(s){var e=document.querySelector(s);if(e){e.click();return true}return false}"
            + "var sh=document.querySelector('#shv.on');if(sh)return hit('#shclose');"
            + "var ss=document.querySelector('#ssheet');if(ss&&!ss.hidden){ss.hidden=true;return true}"
            + "var w=document.querySelector('#watch.on');if(w)return hit(w.classList.contains('mini')?'#mclose':'#wback');"
            + "var f=document.querySelector('#feed.view.on');if(f)return hit('#back');"
            + "return false})()";

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(USER_AGENT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new AssetWebViewClient());
        web.setWebChromeClient(new ChromeClient());

        // Always load the shell explicitly. Restoring a WebBackForwardList after
        // process death can leave a blank WebView, and configChanges already
        // prevents reloads on rotation.
        web.loadUrl(startUrl(getIntent()));
    }

    /** ACTION_SEND ("share a YouTube link to Shelf") maps to the app's ?url= launch handler. */
    private String startUrl(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.trim().length() > 0) {
                return BASE_URL + "?url=" + Uri.encode(shared.trim());
            }
        }
        return BASE_URL;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web != null) web.loadUrl(startUrl(intent));
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (web == null) {
            super.onBackPressed();
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        web.evaluateJavascript(BACK_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(value)) finish();
            }
        });
    }

    private void openExternal(Uri uri) {
        if (uri == null) return;
        if (BASE_HOST.equals(uri.getHost())) {
            web.loadUrl(uri.toString());
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app available to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean override(Uri uri, boolean mainFrame) {
        if (uri == null || BASE_HOST.equals(uri.getHost())) return false;
        String scheme = uri.getScheme();
        if (mainFrame && ("http".equals(scheme) || "https".equals(scheme))) {
            openExternal(uri);
            return true;
        }
        if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme)
                || "geo".equals(scheme) || "intent".equals(scheme)) {
            openExternal(uri);
            return true;
        }
        // Subframe navigations (e.g. the YouTube iframe) must stay inside the WebView.
        return false;
    }

    private static String mimeType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript";
        if (path.endsWith(".json")) return "application/manifest+json";
        if (path.endsWith(".webmanifest")) return "application/manifest+json";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    private WebResourceResponse serveAsset(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        if (path.contains("..") || path.contains("\\")) return notFound("text/plain");

        // A service worker would take over requests through ServiceWorkerClient,
        // bypassing this interceptor. Everything is already bundled, so refuse it.
        if ("/sw.js".equals(path)) return notFound("application/javascript");

        String mime = mimeType(path);
        try {
            InputStream in = getAssets().open("www" + path);
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
        } catch (IOException e) {
            return notFound("text/plain");
        }
    }

    private static WebResourceResponse notFound(String mime) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "utf-8", 404, "Not Found", headers,
                new ByteArrayInputStream(new byte[0]));
    }

    private final class AssetWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri != null && BASE_HOST.equals(uri.getHost())) return serveAsset(uri);
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return override(request.getUrl(), request.isForMainFrame());
        }

        /** Still called on API 23. */
        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return override(Uri.parse(url), true);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            // Only surface failures of the app shell; remote API/image failures are
            // handled by the page itself and should not blank the UI.
            if (request != null && request.isForMainFrame()
                    && request.getUrl() != null && BASE_HOST.equals(request.getUrl().getHost())) {
                Toast.makeText(MainActivity.this, "App shell failed to load", Toast.LENGTH_LONG).show();
            }
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            // target="_blank" and window.open(): route to the user's browser/app.
            WebView popup = new WebView(view.getContext());
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    openExternal(request.getUrl());
                    return true;
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    openExternal(Uri.parse(url));
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            FrameLayout full = new FrameLayout(MainActivity.this);
            full.setBackgroundColor(Color.BLACK);
            full.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            root.addView(full, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        ViewGroup parent = (ViewGroup) customView.getParent();
        if (parent != null) {
            parent.removeView(customView);
            root.removeView(parent);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (web != null) web.setVisibility(View.VISIBLE);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }
}
