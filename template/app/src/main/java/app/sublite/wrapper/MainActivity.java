package app.sublite.wrapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.Manifest;
import android.app.DownloadManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.json.JSONObject;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

public class MainActivity extends Activity {
  private WebView web;
  private String homeHost = "";
  private String startUrl = "__START_URL__";
  private boolean started = false;
  private boolean dnsSettingsOpened = false;
  private AlertDialog dnsDialog;

  private FrameLayout topBanner;
  private FrameLayout bottomBanner;
  private AdView banner;
  private String bannerUnit = "";
  private String bannerPos = "";

  private volatile InterstitialAd interstitial;
  private String interstitialUnit = "";
  private boolean interstitialLoading = false;
  private boolean interstitialShowWhenReady = false;

  private volatile RewardedAd rewarded;
  private String rewardedUnit = "";
  private boolean rewardedLoading = false;
  private boolean rewardedShowWhenReady = false;

  private final Handler colorHandler = new Handler();
  private final Runnable colorWatcher = new Runnable() {
    @Override
    public void run() {
      if (web != null) updateStatusBarFromPage(web);
      colorHandler.postDelayed(this, 500);
    }
  };

  // ---------- links ----------
  private boolean isInternal(String url) {
    if (url == null) return false;
    if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("javascript:")) return true;
    if (!url.startsWith("http")) return false;
    if (homeHost.length() == 0) return false;
    String host = Uri.parse(url).getHost();
    if (host == null) return false;
    host = host.toLowerCase();
    return host.equals(homeHost) || host.endsWith("." + homeHost);
  }

  private void openExternally(String url) {
    try {
      Intent intent;
      if (url.startsWith("intent:")) {
        intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
      } else {
        intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      }
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    } catch (Exception first) {
      try {
        String fallback = Uri.parse(url).getQueryParameter("browser_fallback_url");
        if (fallback != null) {
          Intent web2 = new Intent(Intent.ACTION_VIEW, Uri.parse(fallback));
          web2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          startActivity(web2);
        }
      } catch (Exception ignored) { }
    }
  }

  // ---------- status bar ----------
  private void updateStatusBarFromPage(WebView view) {
    view.evaluateJavascript(
      "(function(){var m=document.querySelector('meta[name=theme-color]');var c=m&&m.content;" +
      "if(!c){c=getComputedStyle(document.body).backgroundColor;if(!c||c==='rgba(0, 0, 0, 0)'){c=getComputedStyle(document.documentElement).backgroundColor;}}" +
      "var x=document.createElement('canvas').getContext('2d');if(!x||!c)return '';x.fillStyle=c;return x.fillStyle;})()",
      value -> {
        if (value == null) return;
        String c = value.trim();
        if (c.length() >= 2 && c.charAt(0) == '"' && c.charAt(c.length() - 1) == '"') {
          c = c.substring(1, c.length() - 1);
        }
        if (c.isEmpty() || "null".equals(c)) return;
        try {
          int color = Color.parseColor(c);
          getWindow().setStatusBarColor(color);
          getWindow().setNavigationBarColor(color);
        } catch (Exception ignored) { }
      }
    );
  }

  // ---------- Private DNS gate ----------
  private boolean privateDnsOn() {
    if (Build.VERSION.SDK_INT < 28) return false;
    try {
      String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
      if ("hostname".equals(mode)) return true;
    } catch (Exception ignored) { }
    try {
      ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      if (cm == null) return false;
      Network n = cm.getActiveNetwork();
      if (n == null) return false;
      LinkProperties lp = cm.getLinkProperties(n);
      return lp != null && lp.getPrivateDnsServerName() != null;
    } catch (Exception e) {
      return false;
    }
  }

  private void openDnsSettings() {
    String[] actions = { "android.settings.PRIVATE_DNS_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS };
    for (String a : actions) {
      try { startActivity(new Intent(a)); return; } catch (Exception ignored) { }
    }
  }

  private void showDnsBlock() {
    if (dnsDialog != null && dnsDialog.isShowing()) return;
    dnsDialog = new AlertDialog.Builder(this)
      .setTitle("Private DNS")
      .setMessage("இந்த ஆப்பைப் பயன்படுத்த Private DNS-ஐ OFF செய்யவும்.\n\nPlease turn OFF Private DNS to use this app.")
      .setCancelable(false)
      .setPositiveButton("Settings", (d, w) -> openDnsSettings())
      .setNegativeButton("Exit", (d, w) -> finish())
      .create();
    dnsDialog.show();
    if (!dnsSettingsOpened) {
      dnsSettingsOpened = true;
      openDnsSettings();
    }
  }

  private void checkGateAndStart() {
    if (F.PRIVATE_DNS_BLOCK && privateDnsOn()) {
      showDnsBlock();
      return;
    }
    if (dnsDialog != null) { dnsDialog.dismiss(); dnsDialog = null; }
    if (!started && web != null) {
      started = true;
      web.loadUrl(startUrl);
    }
  }

  // ---------- AdMob ----------
  private void emit(String type, String event, String message, JSONObject extra) {
    try {
      JSONObject d = extra != null ? extra : new JSONObject();
      d.put("type", type);
      d.put("event", event);
      if (message != null) d.put("message", message);
      final String js = "window.dispatchEvent(new CustomEvent('sublite-ad',{detail:" + d.toString() + "}))";
      runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    } catch (Exception ignored) { }
  }

  private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

  private AdSize bannerSize() {
    DisplayMetrics m = getResources().getDisplayMetrics();
    int widthDp = (int) (m.widthPixels / m.density);
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, widthDp);
  }

  private void showBannerNative(String unitId, String position) {
    if (blank(unitId)) { emit("banner", "error", "missing ad unit id", null); return; }
    String id = unitId.trim();
    String pos = "top".equalsIgnoreCase(position) ? "top" : "bottom";
    if (banner != null && id.equals(bannerUnit) && pos.equals(bannerPos)) return;
    hideBannerNative();
    bannerUnit = id;
    bannerPos = pos;
    FrameLayout host = "top".equals(pos) ? topBanner : bottomBanner;
    banner = new AdView(this);
    banner.setAdUnitId(id);
    banner.setAdSize(bannerSize());
    banner.setAdListener(new AdListener() {
      @Override public void onAdLoaded() { emit("banner", "loaded", null, null); }
      @Override public void onAdFailedToLoad(LoadAdError e) { emit("banner", "error", e.getMessage(), null); }
      @Override public void onAdClicked() { emit("banner", "clicked", null, null); }
      @Override public void onAdOpened() { emit("banner", "opened", null, null); }
      @Override public void onAdClosed() { emit("banner", "closed", null, null); }
    });
    host.addView(banner);
    host.setVisibility(View.VISIBLE);
    banner.loadAd(new AdRequest.Builder().build());
  }

  private void hideBannerNative() {
    if (banner != null) {
      banner.destroy();
      banner = null;
    }
    topBanner.removeAllViews();
    bottomBanner.removeAllViews();
    topBanner.setVisibility(View.GONE);
    bottomBanner.setVisibility(View.GONE);
    bannerUnit = "";
    bannerPos = "";
  }

  private void loadInterstitialNative(String unitId, boolean showAfter) {
    if (blank(unitId)) { emit("interstitial", "error", "missing ad unit id", null); return; }
    String id = unitId.trim();
    if (interstitial != null && id.equals(interstitialUnit)) {
      if (showAfter) showLoadedInterstitial(); else emit("interstitial", "loaded", null, null);
      return;
    }
    if (showAfter) interstitialShowWhenReady = true;
    if (interstitialLoading && id.equals(interstitialUnit)) return;
    interstitialUnit = id;
    interstitial = null;
    interstitialLoading = true;
    InterstitialAd.load(this, id, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
      @Override public void onAdLoaded(InterstitialAd ad) {
        interstitialLoading = false;
        interstitial = ad;
        emit("interstitial", "loaded", null, null);
        if (interstitialShowWhenReady) showLoadedInterstitial();
      }
      @Override public void onAdFailedToLoad(LoadAdError e) {
        interstitialLoading = false;
        interstitialShowWhenReady = false;
        interstitial = null;
        emit("interstitial", "error", e.getMessage(), null);
      }
    });
  }

  private void showLoadedInterstitial() {
    InterstitialAd ad = interstitial;
    interstitialShowWhenReady = false;
    if (ad == null) { emit("interstitial", "not_ready", null, null); return; }
    interstitial = null;
    ad.setFullScreenContentCallback(new FullScreenContentCallback() {
      @Override public void onAdShowedFullScreenContent() { emit("interstitial", "shown", null, null); }
      @Override public void onAdDismissedFullScreenContent() { emit("interstitial", "closed", null, null); }
      @Override public void onAdFailedToShowFullScreenContent(AdError e) { emit("interstitial", "error", e.getMessage(), null); }
      @Override public void onAdClicked() { emit("interstitial", "clicked", null, null); }
    });
    ad.show(this);
  }

  private void loadRewardedNative(String unitId, boolean showAfter) {
    if (blank(unitId)) { emit("rewarded", "error", "missing ad unit id", null); return; }
    String id = unitId.trim();
    if (rewarded != null && id.equals(rewardedUnit)) {
      if (showAfter) showLoadedRewarded(); else emit("rewarded", "loaded", null, null);
      return;
    }
    if (showAfter) rewardedShowWhenReady = true;
    if (rewardedLoading && id.equals(rewardedUnit)) return;
    rewardedUnit = id;
    rewarded = null;
    rewardedLoading = true;
    RewardedAd.load(this, id, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
      @Override public void onAdLoaded(RewardedAd ad) {
        rewardedLoading = false;
        rewarded = ad;
        emit("rewarded", "loaded", null, null);
        if (rewardedShowWhenReady) showLoadedRewarded();
      }
      @Override public void onAdFailedToLoad(LoadAdError e) {
        rewardedLoading = false;
        rewardedShowWhenReady = false;
        rewarded = null;
        emit("rewarded", "error", e.getMessage(), null);
      }
    });
  }

  private void showLoadedRewarded() {
    RewardedAd ad = rewarded;
    rewardedShowWhenReady = false;
    if (ad == null) { emit("rewarded", "not_ready", null, null); return; }
    rewarded = null;
    ad.setFullScreenContentCallback(new FullScreenContentCallback() {
      @Override public void onAdShowedFullScreenContent() { emit("rewarded", "shown", null, null); }
      @Override public void onAdDismissedFullScreenContent() { emit("rewarded", "closed", null, null); }
      @Override public void onAdFailedToShowFullScreenContent(AdError e) { emit("rewarded", "error", e.getMessage(), null); }
      @Override public void onAdClicked() { emit("rewarded", "clicked", null, null); }
    });
    ad.show(this, item -> {
      try {
        JSONObject x = new JSONObject();
        x.put("amount", item.getAmount());
        x.put("rewardType", item.getType());
        emit("rewarded", "reward", null, x);
      } catch (Exception ignored) { }
    });
  }

  /** window.SubliteAds — called from the page's JavaScript. */
  public class AdBridge {
    @JavascriptInterface public void showBanner(String unitId) { runOnUiThread(() -> showBannerNative(unitId, "bottom")); }
    @JavascriptInterface public void showBannerAt(String unitId, String position) { runOnUiThread(() -> showBannerNative(unitId, position)); }
    @JavascriptInterface public void hideBanner() { runOnUiThread(() -> hideBannerNative()); }
    @JavascriptInterface public void loadInterstitial(String unitId) { runOnUiThread(() -> loadInterstitialNative(unitId, false)); }
    @JavascriptInterface public void showInterstitial(String unitId) { runOnUiThread(() -> loadInterstitialNative(unitId, true)); }
    @JavascriptInterface public boolean isInterstitialReady() { return interstitial != null; }
    @JavascriptInterface public void loadRewarded(String unitId) { runOnUiThread(() -> loadRewardedNative(unitId, false)); }
    @JavascriptInterface public void showRewarded(String unitId) { runOnUiThread(() -> loadRewardedNative(unitId, true)); }
    @JavascriptInterface public boolean isRewardedReady() { return rewarded != null; }
    @JavascriptInterface public boolean isAvailable() { return true; }
  }

  // ---------- lifecycle ----------
  private SwipeRefreshLayout swipe;
  private View splashView;
  private ValueCallback<Uri[]> fileCallback;
  private PermissionRequest pendingWebPermission;
  private GeolocationPermissions.Callback pendingGeoCallback;
  private String pendingGeoOrigin;
  private static final int REQ_FILE = 41;
  private static final int REQ_MEDIA = 42;
  private static final int REQ_GEO = 43;

  private void hideSystemBars() {
    if (!F.FULLSCREEN) return;
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) hideSystemBars();
  }

  private void removeSplash() {
    if (splashView != null) {
      View s = splashView;
      splashView = null;
      s.animate().alpha(0f).setDuration(250).withEndAction(() -> {
        if (s.getParent() instanceof FrameLayout) ((FrameLayout) s.getParent()).removeView(s);
      }).start();
    }
  }

  private void showOfflinePage(WebView view, String failingUrl) {
    String safe = failingUrl == null ? "" : failingUrl.replace("'", "%27").replace("<", "%3C");
    String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
      + "<meta name='theme-color' content='#0d0f0d'></head>"
      + "<body style='margin:0;background:#0d0f0d;color:#e8f5e0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
      + "<div><div style='font-size:56px'>&#128246;</div><h2>Internet இல்லை</h2><p style='opacity:.7'>No internet connection</p>"
      + "<button onclick=\"location.href='" + safe + "'\" style='margin-top:16px;padding:12px 28px;border:0;border-radius:10px;background:#9be15d;color:#0d0f0d;font-weight:bold;font-size:16px'>மீண்டும் முயற்சி / Retry</button></div></body></html>";
    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", failingUrl);
  }

  private boolean hasPerm(String p) {
    return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    super.onRequestPermissionsResult(code, perms, results);
    boolean ok = results.length > 0;
    for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
    if (code == REQ_MEDIA && pendingWebPermission != null) {
      if (ok) pendingWebPermission.grant(pendingWebPermission.getResources()); else pendingWebPermission.deny();
      pendingWebPermission = null;
    } else if (code == REQ_GEO && pendingGeoCallback != null) {
      pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
      pendingGeoCallback = null;
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_FILE && fileCallback != null) {
      fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
      fileCallback = null;
    }
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Window window = getWindow();
    window.setStatusBarColor(Color.BLACK);
    window.setNavigationBarColor(Color.BLACK);
    if (F.PORTRAIT_LOCK) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    if (F.KEEP_AWAKE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    hideSystemBars();
    if (startUrl.startsWith("http")) {
      String h = Uri.parse(startUrl).getHost();
      if (h != null) homeHost = h.toLowerCase();
    }

    if (F.ADMOB) {
      new Thread(() -> {
        try { MobileAds.initialize(this, status -> { }); } catch (Exception ignored) { }
      }).start();
    }

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    topBanner = new FrameLayout(this);
    topBanner.setVisibility(View.GONE);
    bottomBanner = new FrameLayout(this);
    bottomBanner.setVisibility(View.GONE);

    web = new WebView(this);
    if (F.NO_VIBRATION) web.setHapticFeedbackEnabled(false);
    WebSettings ws = web.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setDomStorageEnabled(true);
    ws.setDatabaseEnabled(true);
    ws.setAllowFileAccess(true);
    ws.setLoadWithOverviewMode(true);
    ws.setUseWideViewPort(true);
    ws.setMediaPlaybackRequiresUserGesture(false);
    ws.setJavaScriptCanOpenWindowsAutomatically(true);
    ws.setSupportMultipleWindows(true);
    ws.setGeolocationEnabled(F.LOCATION);
    if (F.NO_ZOOM) {
      ws.setSupportZoom(false);
      ws.setBuiltInZoomControls(false);
    } else {
      ws.setSupportZoom(true);
      ws.setBuiltInZoomControls(true);
      ws.setDisplayZoomControls(false);
    }
    if (F.ADMOB) web.addJavascriptInterface(new AdBridge(), "SubliteAds");

    if (F.DOWNLOADS) {
      web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
        try {
          DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
          String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
          String cookies = CookieManager.getInstance().getCookie(url);
          if (cookies != null) req.addRequestHeader("Cookie", cookies);
          req.addRequestHeader("User-Agent", userAgent);
          req.setMimeType(mimeType);
          req.setTitle(fileName);
          req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
          req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
          ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
          Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
          openExternally(url);
        }
      });
    }

    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message resultMsg) {
        WebView probe = new WebView(view.getContext());
        probe.setWebViewClient(new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (!F.EXTERNAL_LINKS && url.startsWith("http")) {
              web.loadUrl(url);
            } else if (isInternal(url)) {
              web.loadUrl(url);
            } else {
              openExternally(url);
            }
            return true;
          }
        });
        ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
        resultMsg.sendToTarget();
        return true;
      }

      @Override
      public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        if (!F.FILE_UPLOAD) return false;
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        try {
          startActivityForResult(params.createIntent(), REQ_FILE);
          return true;
        } catch (Exception e) {
          fileCallback = null;
          return false;
        }
      }

      @Override
      public void onPermissionRequest(PermissionRequest request) {
        if (!F.CAMERA_MIC) { request.deny(); return; }
        runOnUiThread(() -> {
          java.util.ArrayList<String> need = new java.util.ArrayList<>();
          for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !hasPerm(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !hasPerm(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
          }
          if (need.isEmpty()) { request.grant(request.getResources()); return; }
          pendingWebPermission = request;
          requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
        });
      }

      @Override
      public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        if (!F.LOCATION) { callback.invoke(origin, false, false); return; }
        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) { callback.invoke(origin, true, false); return; }
        pendingGeoOrigin = origin;
        pendingGeoCallback = callback;
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, REQ_GEO);
      }
    });

    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (!F.EXTERNAL_LINKS && url.startsWith("http")) return false;
        if (isInternal(url)) return false;
        openExternally(url);
        return true;
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (F.OFFLINE_PAGE && request.isForMainFrame()) showOfflinePage(view, request.getUrl().toString());
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        if (swipe != null) swipe.setRefreshing(false);
        removeSplash();
        StringBuilder js = new StringBuilder("(function(){var old=document.getElementById('sublite-select');if(old)old.remove();");
        if (F.NO_TEXT_SELECT) {
          js.append("if(!document.getElementById('sublite-no-select')){var s=document.createElement('style');s.id='sublite-no-select';")
            .append("s.textContent='*{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}")
            .append("input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important}';")
            .append("(document.head||document.documentElement).appendChild(s);")
            .append("var ed=function(t){return t&&t.closest&&t.closest('input,textarea,[contenteditable]');};")
            .append("document.addEventListener('contextmenu',function(e){if(!ed(e.target))e.preventDefault();},true);")
            .append("document.addEventListener('selectstart',function(e){if(!ed(e.target))e.preventDefault();},true);}");
        }
        if (F.NO_ZOOM) {
          js.append("var vp=document.querySelector('meta[name=viewport]');if(!vp){vp=document.createElement('meta');vp.name='viewport';(document.head||document.documentElement).appendChild(vp);}")
            .append("vp.content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no';");
        }
        if (F.ADMOB) {
          js.append("var b=document.querySelector('meta[name=admob-banner-id]');")
            .append("if(b&&b.content&&window.SubliteAds){var p=document.querySelector('meta[name=admob-banner-position]');")
            .append("SubliteAds.showBannerAt(b.content,p?p.content:'bottom');}")
            .append("window.dispatchEvent(new Event('sublite-ads-ready'));");
        }
        js.append("})()");
        view.evaluateJavascript(js.toString(), null);
        if (F.STATUS_BAR_AUTO) {
          colorHandler.removeCallbacks(colorWatcher);
          colorHandler.post(colorWatcher);
        }
      }
    });

    swipe = new SwipeRefreshLayout(this);
    swipe.addView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    swipe.setEnabled(F.PULL_TO_REFRESH);
    swipe.setOnChildScrollUpCallback((parent, child) -> web != null && web.getScrollY() > 0);
    swipe.setOnRefreshListener(() -> { if (web != null) web.reload(); });

    root.addView(topBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(swipe, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    root.addView(bottomBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    FrameLayout container = new FrameLayout(this);
    container.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    if (F.SPLASH_SCREEN) {
      FrameLayout splash = new FrameLayout(this);
      splash.setBackgroundColor(Color.BLACK);
      ImageView logo = new ImageView(this);
      logo.setImageResource(R.mipmap.ic_launcher);
      int size = (int) (112 * getResources().getDisplayMetrics().density);
      splash.addView(logo, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
      splash.setClickable(true);
      container.addView(splash, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
      splashView = splash;
      colorHandler.postDelayed(this::removeSplash, 8000);
    }
    setContentView(container);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (web != null) web.onResume();
    if (banner != null) banner.resume();
    hideSystemBars();
    checkGateAndStart();
  }

  @Override
  protected void onPause() {
    if (banner != null) banner.pause();
    if (web != null) web.onPause();
    super.onPause();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      if (F.BACK_NAVIGATION && web != null && web.canGoBack()) {
        web.goBack();
        return true;
      }
      if (F.EXIT_CONFIRM) {
        new AlertDialog.Builder(this)
          .setMessage("வெளியேறவா? / Exit the app?")
          .setPositiveButton("Exit", (d, w) -> finish())
          .setNegativeButton("Cancel", null)
          .show();
        return true;
      }
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  protected void onDestroy() {
    colorHandler.removeCallbacksAndMessages(null);
    if (dnsDialog != null) { dnsDialog.dismiss(); dnsDialog = null; }
    if (banner != null) { banner.destroy(); banner = null; }
    if (web != null) { web.destroy(); web = null; }
    super.onDestroy();
  }
}
