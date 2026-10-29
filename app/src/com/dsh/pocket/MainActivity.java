package com.dsh.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.os.Environment;

/**
 * A thin shell around the already-working remote web surface: one WebView bound to
 * the fixed relay origin, with cookies persisted so a single pairing lasts.
 */
public final class MainActivity extends Activity {

    private static final String PREFS = "dsh-shell";
    private static final String KEY_ORIGIN = "origin";
    private static final String DEFAULT_ORIGIN = "https://YOUR-RELAY.dsh-market.com";

    /** Mobile layout rules applied inside the shell regardless of the server skin. */
    /** Where the shell looks for a newer build; the path is served by the same box. */
    private static final String UPDATE_BASE = "https://YOUR-UPDATE-HOST.example.com";
    /** Update endpoint on the local network: reachable without any tunnel. */
    private static final String LOCAL_UPDATE_BASE = "http://YOUR-LAN-IP:8897";

    private static final int VERSION_CODE = 15;

    private static final String MOBILE_CSS = "@media (max-width:900px){[data-dsh-skin-layer]{display:none!important}header[class*='_header']:has([class*='_titleRow']){min-height:0!important;padding:8px 12px 8px 52px!important}header[class*='_header'] [class*='_tabs']{display:none!important}[class*='_crumbSep']{display:none!important}[data-composer-card] button,[data-composer-card] [role='button']{min-height:36px!important;min-width:36px!important;padding:0 10px!important;font-size:13px!important;border-radius:10px!important}[data-composer-card] .dsh-cu-chip,[data-composer-card] .dshp-rewind,[data-composer-card] [title*='\u6e32\u67d3']{display:none!important}[data-composer-card] button.control{max-width:88px!important;overflow:hidden!important;white-space:nowrap!important}[class*='_bubble']{padding:8px 12px!important;border-radius:14px!important}[class*='_headerUtilities']{gap:2px!important}div[style*='position: fixed'][style*='2147483000'],button[style*='2147483000']{display:none!important}[class*='citeciter']{display:none!important}.fm-widget{display:none!important}body{--dsh-content-font-size:15px!important}}";

    private WebView web;
    private SharedPreferences prefs;
    private TextView statusBar;
    private ValueCallback<Uri[]> pendingFileCallback;
    private static final int REQ_FILE = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusBar = new TextView(this);
        statusBar.setTextColor(Color.parseColor("#E9EBEE"));
        statusBar.setTextSize(12);
        statusBar.setBackgroundColor(Color.parseColor("#CC111318"));
        statusBar.setPadding(24, 18, 24, 18);
        statusBar.setVisibility(TextView.GONE);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.TOP;
        root.addView(statusBar, statusParams);

        Button pair = new Button(this);
        pair.setText("配");
        pair.setTextSize(13);
        pair.setAlpha(0.72f);
        pair.setOnClickListener(v -> askForPairLink());
        pair.setOnLongClickListener(v -> {
            clearSession();
            return true;
        });
        FrameLayout.LayoutParams pairParams = new FrameLayout.LayoutParams(dp(52), dp(52));
        pairParams.gravity = Gravity.TOP | Gravity.END;
        pairParams.topMargin = dp(8);
        pairParams.rightMargin = dp(8);
        root.addView(pair, pairParams);

        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // Locks the CSS viewport to the device width: no 980px desktop layout scaled
        // down to finger size, which is what makes the buttons unhittable.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (scheme.equals("http") || scheme.equals("https")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                startInjection();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                injectMobileCss(view);
                // Visible diagnostics: viewport width, DPR, and whether the mobile skin applied.
                view.evaluateJavascript(
                        "(function(){var st=document.getElementById('dsh-pocket-css');"
                      + "var cites=document.querySelectorAll(\"[class*='citeciter']\").length;"
                      + "var brg=document.querySelectorAll('div[style*=\\\"2147483000\\\"]').length;"
                      + "var vis=0;document.querySelectorAll(\"[class*='citeciter']\").forEach(function(e){if(getComputedStyle(e).display!=='none')vis++});"
                      + "return 'w='+window.innerWidth+' css='+(st?'ok':'none')"
                      + "+' cites='+cites+' vis='+vis+' brg='+brg;})()",
                        value -> showSticky("v1.3 " + value.replace("\"", "")));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (!request.isForMainFrame()) return;
                showSticky("加载失败：" + error.getErrorCode() + " " + error.getDescription());
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                showSticky("证书错误：" + error.getPrimaryError());
                handler.cancel();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = callback;
                try {
                    Intent picker = params.createIntent();
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(picker, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFileCallback = null;
                    showSticky("打开文件选择器失败：" + e.getMessage());
                    return false;
                }
            }
        });

        // Downloads inside the app page: hand them to the system downloader, carrying
        // the page's cookies because dsh gates its file routes on the session.
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                android.app.DownloadManager.Request request =
                        new android.app.DownloadManager.Request(Uri.parse(url));
                if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (mimeType != null) request.setMimeType(mimeType);
                request.setTitle(name);
                request.setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                android.app.DownloadManager manager =
                        (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                manager.enqueue(request);
                showSticky("已开始下载：" + name);
            } catch (Exception e) {
                showSticky("下载失败：" + e.getMessage());
            }
        });

        String origin = prefs.getString(KEY_ORIGIN, DEFAULT_ORIGIN);
        if (origin.contains("YOUR-RELAY")) {
            askForOrigin();
        } else {
            web.loadUrl(origin);
        }

        web.postDelayed(this::checkUpdate, 4000);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Paste the pairing link copied from the desktop panel. */

    /** First launch on a fresh install: ask where this shell should point. */
    private void askForOrigin() {
        EditText field = new EditText(this);
        field.setHint("https://xxxx.dsh-market.com");
        field.setTextSize(13);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("DSH 远程入口")
                .setMessage("填你电脑端生成的中继域名，或者直接扫码配对。")
                .setView(field)
                .setPositiveButton("保存", (dialog, which) -> {
                    String raw = field.getText().toString().trim();
                    if (raw.isEmpty()) {
                        askForOrigin();
                        return;
                    }
                    if (!raw.startsWith("http")) raw = "https://" + raw;
                    String normalised = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
                    prefs.edit().putString(KEY_ORIGIN, normalised).apply();
                    web.loadUrl(normalised);
                })
                .setNeutralButton("扫码配对", (dialog, which) ->
                        startActivityForResult(new Intent(this, ScanActivity.class), 200))
                .setCancelable(false)
                .show();
    }

    private void askForPairLink() {
        EditText field = new EditText(this);
        field.setHint("https://<id>.dsh-market.com/pair-accept?pair=…");
        field.setTextSize(13);
        field.setSingleLine(false);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("配对链接")
                .setMessage("电脑上「远程访问」面板里点一下复制，把整条链接粘到这里。")
                .setView(field)
                .setPositiveButton("打开", (dialog, which) -> openPairLink(field.getText().toString().trim()))
                .setNeutralButton("扫码", (dialog, which) ->
                        startActivityForResult(new Intent(this, ScanActivity.class), 200))
                .setNegativeButton("取消", null)
                .show();
    }


    /** Load one pairing link and remember its origin for later launches. */
    private void openPairLink(String raw) {
        if (raw == null) return;
        String link = raw.trim();
        if (link.isEmpty()) return;
        if (!link.startsWith("http")) link = "https://" + link;
        try {
            Uri target = Uri.parse(link);
            if (target.getHost() != null) {
                prefs.edit().putString(KEY_ORIGIN, target.getScheme() + "://" + target.getHost()).apply();
            }
        } catch (Exception ignored) {
        }
        showSticky("正在配对…");
        web.loadUrl(link);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200 && resultCode == RESULT_OK && data != null) {
            openPairLink(data.getStringExtra("url"));
        }
        if (requestCode == REQ_FILE) {
            if (pendingFileCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            pendingFileCallback.onReceiveValue(results);
            pendingFileCallback = null;
        }
    }

    private void clearSession() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        prefs.edit().remove(KEY_ORIGIN).apply();
        web.clearCache(true);
        showStatus("已清除配对");
        askForOrigin();
    }


    /** Injected on every navigation: the shell owns its own mobile layout instead of
     *  trusting a server-side skin that may not have been loaded. */
    private final android.os.Handler injector = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable injectTick = new Runnable() {
        @Override
        public void run() {
            injectMobileCss(web);
            injector.postDelayed(this, 900);
        }
    };

    private void startInjection() {
        injector.removeCallbacks(injectTick);
        injector.post(injectTick);
    }

    private void injectMobileCss(WebView view) {
        String js = "(function(){"
                + "var s=document.getElementById('dsh-pocket-css');"
                + "if(!s){s=document.createElement('style');s.id='dsh-pocket-css';document.head.appendChild(s);}"
                + "s.textContent=" + org.json.JSONObject.quote(MOBILE_CSS) + ";"
                + "var hide=function(e){if(e&&e.style){e.style.setProperty('display','none','important');}};"
                + "var kill=function(sel){var n=document.querySelectorAll(sel);for(var i=0;i<n.length;i++)hide(n[i]);};"
                + "kill('div[style*=\"2147483000\"]');"
                + "kill('button[style*=\"2147483000\"]');"
                + "kill('[class*=\"citeciter\"]');"
                + "kill('.fm-widget');"
                + "})()";
        view.evaluateJavascript(js, null);
    }


    /** Ask the update endpoint for a newer build and hand the APK to the system installer. */
    private void checkUpdate() {
        String override = prefs.getString("updateBase", "");
        String[] candidates = {override, LOCAL_UPDATE_BASE, UPDATE_BASE};
        new Thread(() -> {
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                if (probeUpdate(candidate)) return;
            }
        }, "update-check").start();
    }

    /** One update endpoint: true when it answered and the version check completed. */
    private boolean probeUpdate(String base) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(base + "/version.json").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return false;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = c.getInputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
            org.json.JSONObject json = new org.json.JSONObject(buf.toString("UTF-8"));
            int latest = json.optInt("versionCode", 0);
            String file = json.optString("file", "");
            if (latest > VERSION_CODE && !file.isEmpty()) downloadUpdate(base + "/" + file, latest);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }


    private void downloadUpdate(String url, int versionCode) {
        try {
            final android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            request.setTitle("DSH Pocket " + versionCode);
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this,
                    android.os.Environment.DIRECTORY_DOWNLOADS, "dsh-pocket-update.apk");
            final long id = dm.enqueue(request);
            showSticky("正在下载新版本 " + versionCode + "…");

            new Thread(() -> {
                for (int i = 0; i < 180; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    android.database.Cursor cursor =
                            dm.query(new android.app.DownloadManager.Query().setFilterById(id));
                    if (cursor == null) continue;
                    if (!cursor.moveToFirst()) {
                        cursor.close();
                        continue;
                    }
                    int status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
                    cursor.close();
                    if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        Uri apk = dm.getUriForDownloadedFile(id);
                        Intent install = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(apk, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                        runOnUiThread(() -> {
                            try {
                                startActivity(install);
                            } catch (Exception e) {
                                showSticky("安装失败：" + e.getMessage());
                            }
                        });
                        return;
                    }
                    if (status == android.app.DownloadManager.STATUS_FAILED) {
                        runOnUiThread(() -> showSticky("更新下载失败"));
                        return;
                    }
                }
            }, "update-watch").start();
        } catch (Exception e) {
            showSticky("更新启动失败：" + e.getMessage());
        }
    }

    private void showStickyLong(String text) { showSticky(text); }

    private void showStatus(String text) {
        statusBar.setText(text);
        statusBar.setVisibility(TextView.VISIBLE);
        statusBar.postDelayed(() -> statusBar.setVisibility(TextView.GONE), 4000);
    }

    private void showSticky(String text) {
        statusBar.setText(text);
        statusBar.setVisibility(TextView.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        injector.removeCallbacks(injectTick);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }
}
