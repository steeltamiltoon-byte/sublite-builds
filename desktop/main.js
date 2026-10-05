const { app, BrowserWindow, shell, dialog, powerSaveBlocker, session } = require("electron");
const path = require("path");
const config = require("./config.json");

const DEFAULTS = {
  externalLinks: true, noTextSelect: true, backNavigation: true, splashScreen: false,
  offlinePage: false, fileUpload: false, cameraMic: false, location: false, downloads: false,
  keepAwake: false, fullscreen: false, noZoom: false, exitConfirm: false,
};
const F = Object.assign({}, DEFAULTS, config.features || {});
const { pathToFileURL } = require("url");
const startUrl = config.startUrl || pathToFileURL(path.join(__dirname, "app", "index.html")).href;
let homeHost = "";
try { homeHost = config.startUrl ? new URL(config.startUrl).hostname.toLowerCase() : ""; } catch (e) {}

function isInternal(url) {
  if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("data:")) return true;
  if (!F.externalLinks && url.startsWith("http")) return true;
  if (!homeHost) return false;
  try {
    const h = new URL(url).hostname.toLowerCase();
    return h === homeHost || h.endsWith("." + homeHost);
  } catch (e) { return false; }
}

const NO_SELECT_CSS = "*{-webkit-user-select:none!important;user-select:none!important}" +
  "input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important}";

function offlineHtml(url) {
  const safe = String(url).replace(/'/g, "%27").replace(/</g, "%3C");
  return "<!doctype html><meta charset=utf-8><body style=\"margin:0;background:#0d0f0d;color:#e8f5e0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center\">" +
    "<div><div style=font-size:56px>&#128246;</div><h2>Internet இல்லை</h2><p style=opacity:.7>No internet connection</p>" +
    "<button onclick=\"location.href='" + safe + "'\" style=\"padding:12px 28px;border:0;border-radius:10px;background:#9be15d;font-weight:bold;font-size:16px\">மீண்டும் முயற்சி / Retry</button></div>";
}

let win = null;
let splash = null;
let allowClose = false;

function createWindow() {
  if (F.splashScreen) {
    splash = new BrowserWindow({ width: 360, height: 360, frame: false, resizable: false, backgroundColor: "#000000", alwaysOnTop: true, icon: path.join(__dirname, "icon.png") });
    splash.loadURL(pathToFileURL(path.join(__dirname, "icon.png")).href);
  }

  win = new BrowserWindow({
    width: 1280,
    height: 800,
    show: !F.splashScreen,
    fullscreen: !!F.fullscreen,
    autoHideMenuBar: true,
    title: config.name,
    icon: path.join(__dirname, "icon.png"),
    webPreferences: { contextIsolation: true, nodeIntegration: false, spellcheck: false },
  });
  win.setMenuBarVisibility(false);
  if (F.keepAwake) powerSaveBlocker.start("prevent-display-sleep");

  const wc = win.webContents;
  wc.setWindowOpenHandler(({ url }) => {
    if (isInternal(url)) { win.loadURL(url); } else { shell.openExternal(url); }
    return { action: "deny" };
  });
  wc.on("will-navigate", (event, url) => {
    if (!isInternal(url)) { event.preventDefault(); shell.openExternal(url); }
  });
  wc.on("did-finish-load", () => {
    if (F.noTextSelect) {
      wc.insertCSS(NO_SELECT_CSS);
      wc.executeJavaScript("document.addEventListener('contextmenu',function(e){var t=e.target;if(!(t&&t.closest&&t.closest('input,textarea,[contenteditable]')))e.preventDefault();},true);");
    }
    if (F.noZoom) wc.setVisualZoomLevelLimits(1, 1);
    if (splash) { splash.destroy(); splash = null; }
    if (!win.isVisible()) win.show();
  });
  wc.on("did-fail-load", (event, code, desc, url, isMainFrame) => {
    if (F.offlinePage && isMainFrame && code !== -3) {
      wc.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(offlineHtml(url)));
    }
    if (splash) { splash.destroy(); splash = null; win.show(); }
  });
  wc.on("before-input-event", (event, input) => {
    if (F.noZoom && (input.control || input.meta) && ["+", "-", "=", "0"].includes(input.key)) event.preventDefault();
    if (F.backNavigation && input.type === "keyDown" && ((input.alt && input.key === "ArrowLeft") || input.key === "BrowserBack")) {
      if (wc.canGoBack()) wc.goBack();
    }
  });
  win.on("app-command", (e, cmd) => {
    if (F.backNavigation && cmd === "browser-backward" && wc.canGoBack()) wc.goBack();
  });
  win.on("close", (e) => {
    if (!F.exitConfirm || allowClose) return;
    const choice = dialog.showMessageBoxSync(win, { type: "question", buttons: ["Exit", "Cancel"], defaultId: 1, message: "வெளியேறவா? / Exit the app?" });
    if (choice === 0) allowClose = true; else e.preventDefault();
  });
  setTimeout(() => { if (splash) { splash.destroy(); splash = null; win.show(); } }, 8000);

  win.loadURL(startUrl);
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, permission, callback) => {
    if (permission === "media") return callback(!!F.cameraMic);
    if (permission === "geolocation") return callback(!!F.location);
    callback(true);
  });
  session.defaultSession.on("will-download", (event) => {
    if (!F.downloads) event.preventDefault();
  });
  if (!F.fileUpload) {
    session.defaultSession.on("file-system-access-restricted", () => {});
  }
  createWindow();
});

app.on("window-all-closed", () => app.quit());
