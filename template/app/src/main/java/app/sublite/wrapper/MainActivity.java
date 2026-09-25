package app.sublite.wrapper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
  private WebView web;
  private String homeHost = "";
  private final Handler colorHandler = new Handler();
  private final Runnable colorWatcher = new Runnable() {
    @Override
    public void run() {
      if (web != null) updateStatusBarFromPage(web);
      colorHandler.postDelayed(this, 500);
    }
  };

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

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Window window = getWindow();
    window.setStatusBarColor(Color.BLACK);
    window.setNavigationBarColor(Color.BLACK);
    String startUrl = "__START_URL__";
    if (startUrl.startsWith("http")) {
      String h = Uri.parse(startUrl).getHost();
      if (h != null) homeHost = h.toLowerCase();
    }

    web = new WebView(this);
    web.setHapticFeedbackEnabled(false);
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
          "(function(){var s=document.getElementById('sublite-no-select');" +
          "if(!s){s=document.createElement('style');s.id='sublite-no-select';" +
          "s.textContent='*:not(input):not(textarea):not([contenteditable=true]){-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important} input,textarea,[contenteditable=true]{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important}';" +
          "(document.head||document.documentElement).appendChild(s);}" +
          "function editable(e){return e.target&&e.target.closest&&e.target.closest('input,textarea,[contenteditable=true]');}" +
          "document.addEventListener('contextmenu',function(e){if(!editable(e))e.preventDefault()},true);" +
          "document.addEventListener('selectstart',function(e){if(!editable(e))e.preventDefault()},true)})()",
          null
        );
        colorHandler.removeCallbacks(colorWatcher);
        colorHandler.post(colorWatcher);
      }
    });

    setContentView(web);
    web.loadUrl(startUrl);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
      web.goBack();
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  protected void onDestroy() {
    colorHandler.removeCallbacks(colorWatcher);
    super.onDestroy();
  }
}
