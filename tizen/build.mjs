// src/ → app/js/app.js (eski Tizen tarayıcıları için ES2017'ye çevrilir), sonra dist/G-Flix.wgt (imzasız) paketlenir.
import { build } from "esbuild";
import { execSync } from "node:child_process";
import { mkdirSync, rmSync, readFileSync, writeFileSync } from "node:fs";

const version = process.env.GFLIX_TV_VERSION || "1.0.0";
await build({
  entryPoints: ["src/app.js"], bundle: true, format: "iife", target: ["chrome56"], minify: true,
  outfile: "app/js/app.js", legalComments: "none", logLevel: "info",
});
const cfg = "app/config.xml";
writeFileSync(cfg, readFileSync(cfg, "utf8").replace(/(<widget[^>]*version=")[^"]+"/, `$1${version}"`));
if (process.argv.includes("--wgt")) {
  rmSync("dist", { recursive: true, force: true }); mkdirSync("dist");
  execSync(`cd app && zip -qr ../dist/tv-${version}.wgt . -x '.*'`);
  console.log(`dist/tv-${version}.wgt (imzasız; TV'ye kurmadan önce Tizen Studio ile imzalanmalı)`);
}
