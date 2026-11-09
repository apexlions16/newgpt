#!/usr/bin/env node
// G-Flix TV sunucusu (Linux PC'de çalışır, ev ağına açıktır).
//  - TV uygulaması kataloğu ve videoları buradan alır.
//  - Parçalı videoları (f.p000, f.p001 …) GitHub'dan akış halinde çekip TV'ye tek dosya gibi aktarır (diske yazmaz).
//  - http://<PC-IP>:8766/tv/ → TV'nin internet tarayıcısı için uygulamanın tarayıcı sürümü.
// Token ve depo adı G-Flix işçisinin ayar dosyasından (~/.config/gflix/worker.env) okunur.
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const { createServer } = require("../desktop/server.js");

function readEnv(file) {
  const out = {};
  try {
    for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
      if (m) out[m[1]] = m[2].replace(/^["']|["']$/g, "");
    }
  } catch (_) { /* yok */ }
  return out;
}

const env = { ...readEnv(path.join(os.homedir(), ".config/gflix/worker.env")), ...process.env };
const [owner, repo] = String(env.GFLIX_REPO || "thedrowned925/g-flix").split("/");
const port = Number(process.argv[2] || env.GFLIX_TV_PORT || 8766);
const dataDir = env.GFLIX_TV_DATA || path.join(os.homedir(), ".local/share/gflix/tv");

const s = createServer({
  dataDir, version: "tv", host: "0.0.0.0", cors: true,
  buildConfig: { owner, repo, token: env.GITHUB_TOKEN || "" },
  mounts: { "/tv/": path.join(__dirname, "app") + path.sep },
});
s.listen(port, "0.0.0.0").then((p) => {
  const ips = Object.values(os.networkInterfaces()).flat().filter((i) => i && i.family === "IPv4" && !i.internal).map((i) => i.address);
  console.log(`G-Flix TV sunucusu hazır (port ${p}).`);
  ips.forEach((ip) => console.log(`  TV'de sunucu adresi: ${ip}   ·   tarayıcı sürümü: http://${ip}:${p}/tv/`));
  if (!env.GITHUB_TOKEN) console.log("  UYARI: GITHUB_TOKEN yok (~/.config/gflix/worker.env). Özel depodaki videolar açılmaz.");
}).catch((e) => { console.error("Sunucu başlatılamadı:", e.message); process.exit(1); });

// Kendini güncelleme: G-Flix işçisi depoyu saatte bir çeker (git pull). Sunucu kodu değişirse çıkar;
// systemd (Restart=always) yeni koduyla hemen yeniden başlatır. TV arayüzü (tizen/app) zaten her istekte diskten okunur.
const watched = [path.join(__dirname, "sunucu.js"), path.join(__dirname, "..", "desktop", "server.js")];
const stamp = () => watched.map((f) => { try { return fs.statSync(f).mtimeMs; } catch (_) { return 0; } }).join(",");
const started = stamp();
setInterval(() => { if (stamp() !== started) { console.log("Sunucu kodu güncellendi, yeniden başlatılıyor…"); process.exit(0); } }, 60_000).unref();
