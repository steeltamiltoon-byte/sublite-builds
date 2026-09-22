package app.sublite.wrapper;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
  private WebView web;
  private String homeHost = "";

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

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    String startUrl = "__START_URL__";
    if (startUrl.startsWith("http")) {
      String h = Uri.parse(startUrl).getHost();
      if (h != null) homeHost = h.toLowerCase();
    }

    web = new WebView(this);
    web.setHapticFeedbackEnabled(false);
    web.setLongClickable(false);
    web.setOnLongClickListener(view -> true);
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
          "s.textContent='*{-webkit-user-select:none!important;user-select:none!important;-webkit-touch-callout:none!important}';" +
          "(document.head||document.documentElement).appendChild(s);}" +
          "document.addEventListener('contextmenu',function(e){e.preventDefault()},true);" +
          "document.addEventListener('selectstart',function(e){e.preventDefault()},true)})()",
          null
        );
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
}
