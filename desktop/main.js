// G-Flix PC (Electron): pencereyi açar, yerel sunucuyu başlatır.
"use strict";
const { app, BrowserWindow, shell, Menu, nativeTheme } = require("electron");
const path = require("path");
const { createServer } = require("./server");

// <video> içindeki birden fazla ses izini (MKV: Türkçe/İngilizce) seçebilmek için
app.commandLine.appendSwitch("enable-blink-features", "AudioVideoTracks");
app.commandLine.appendSwitch("autoplay-policy", "no-user-gesture-required");
nativeTheme.themeSource = "dark";

let buildConfig = {};
try { buildConfig = require("./build-config.json"); } catch (_) { /* geliştirme: anahtar ilk açılışta sorulur */ }

async function start() {
  const server = createServer({ dataDir: app.getPath("userData"), buildConfig, version: app.getVersion() });
  const port = await server.listen(0);
  Menu.setApplicationMenu(null);
  const win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 960, minHeight: 600,
    backgroundColor: "#141414", title: "G-Flix", autoHideMenuBar: true,
    icon: path.join(__dirname, "build", "icon.png"),
    webPreferences: { contextIsolation: true, sandbox: true, preload: path.join(__dirname, "preload.js") },
  });
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("before-input-event", (e, input) => {
    if (input.type === "keyDown" && input.key === "F11") { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (input.type === "keyDown" && input.key === "F12" && input.control && input.shift) win.webContents.toggleDevTools();
  });
  await win.loadURL(`http://127.0.0.1:${port}/`);
  app.on("window-all-closed", () => { server.close(); app.quit(); });
}

app.whenReady().then(start);
