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
    if (privateDnsOn()) {
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
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Window window = getWindow();
    window.setStatusBarColor(Color.BLACK);
    window.setNavigationBarColor(Color.BLACK);
    if (startUrl.startsWith("http")) {
      String h = Uri.parse(startUrl).getHost();
      if (h != null) homeHost = h.toLowerCase();
    }

    new Thread(() -> {
      try { MobileAds.initialize(this, status -> { }); } catch (Exception ignored) { }
    }).start();

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    topBanner = new FrameLayout(this);
    topBanner.setVisibility(View.GONE);
    bottomBanner = new FrameLayout(this);
    bottomBanner.setVisibility(View.GONE);

    web = new WebView(this);
    web.setHapticFeedbackEnabled(false);
    web.setLongClickable(false);
    web.setOnLongClickListener(v -> true);
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
    web.addJavascriptInterface(new AdBridge(), "SubliteAds");

    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message resultMsg) {
        WebView probe = new WebView(view.getContext());
        probe.setWebViewClient(new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isInternal(url)) {
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
    });

    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (isInternal(url)) return false;
        openExternally(url);
        return true;
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        view.evaluateJavascript(
          "(function(){var old=document.getElementById('sublite-select');if(old)old.remove();" +
          "if(!document.getElementById('sublite-no-select')){var s=document.createElement('style');s.id='sublite-no-select';" +
          "s.textContent='*{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}" +
          "input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important}';" +
          "(document.head||document.documentElement).appendChild(s);" +
          "var ed=function(t){return t&&t.closest&&t.closest('input,textarea,[contenteditable]');};" +
          "document.addEventListener('contextmenu',function(e){if(!ed(e.target))e.preventDefault();},true);" +
          "document.addEventListener('selectstart',function(e){if(!ed(e.target))e.preventDefault();},true);}" +
          "var b=document.querySelector('meta[name=admob-banner-id]');" +
          "if(b&&b.content&&window.SubliteAds){var p=document.querySelector('meta[name=admob-banner-position]');" +
          "SubliteAds.showBannerAt(b.content,p?p.content:'bottom');}" +
          "window.dispatchEvent(new Event('sublite-ads-ready'));})()",
          null
        );
        colorHandler.removeCallbacks(colorWatcher);
        colorHandler.post(colorWatcher);
      }
    });

    root.addView(topBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    root.addView(bottomBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    setContentView(root);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (web != null) web.onResume();
    if (banner != null) banner.resume();
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
    if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
      web.goBack();
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  protected void onDestroy() {
    colorHandler.removeCallbacks(colorWatcher);
    if (dnsDialog != null) { dnsDialog.dismiss(); dnsDialog = null; }
    if (banner != null) { banner.destroy(); banner = null; }
    if (web != null) { web.destroy(); web = null; }
    super.onDestroy();
  }
}
