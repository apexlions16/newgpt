// Ekran yığını, odak yardımcıları, ekran klavyesi, kartlar.
import { h, clear } from "../../desktop/renderer/js/dom.js";

// append(null) metin olarak "null" yazmasın
const nativeAppend = Element.prototype.append;
Element.prototype.append = function (...a) { return nativeAppend.apply(this, a.filter((x) => x != null && x !== false)); };

export const stage = () => document.getElementById("stage");
const stack = [];
export const Screens = {
  get top() { return stack[stack.length - 1]; },
  push(s) {
    const prev = Screens.top;
    if (prev) { prev.hide && prev.hide(); prev.el.classList.add("behind"); }
    stack.push(s);
    stage().appendChild(s.el);
    requestAnimationFrame(() => s.el.classList.add("in"));
    s.show && s.show();
  },
  pop() {
    const s = stack.pop();
    if (!s) return;
    s.destroy && s.destroy();
    s.el.classList.remove("in");
    s.el.classList.add("out");
    setTimeout(() => s.el.remove(), 260);
    const top = Screens.top;
    if (top) { top.el.classList.remove("behind"); top.show && top.show(); }
  },
  replaceAll(s) {
    while (stack.length) { const x = stack.pop(); x.destroy && x.destroy(); x.el.remove(); }
    Screens.push(s);
  },
  key(k, repeat) { const t = Screens.top; if (t && t.key) t.key(k, repeat); },
};

export const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/** Ortadaki küçük bildirim. */
export function toast(text, ms = 2600) {
  const t = h("div.toast", text);
  document.body.appendChild(t);
  requestAnimationFrame(() => t.classList.add("in"));
  setTimeout(() => { t.classList.remove("in"); setTimeout(() => t.remove(), 300); }, ms);
}

/** Evet/Hayır penceresi. */
export function confirmBox(text, yes, { yesLabel = "Evet", noLabel = "Vazgeç" } = {}) {
  let i = 1;
  const btns = [h("button.dlg-btn", yesLabel), h("button.dlg-btn", noLabel)];
  const el = h("div.screen.dialog", h("div.dlg-box", h("div.dlg-text", text), h("div.dlg-btns", btns)));
  const paint = () => btns.forEach((b, j) => b.classList.toggle("focus", i === j));
  paint();
  Screens.push({ el, key(k) {
    if (k === "left" || k === "right") { i = 1 - i; paint(); }
    else if (k === "ok") { Screens.pop(); if (i === 0) yes(); }
    else if (k === "back") Screens.pop();
  } });
}

/** Seçenek listesi penceresi. options: [{label, on, pick}] */
export function choiceBox(title, options) {
  let i = Math.max(0, options.findIndex((o) => o.on));
  const items = options.map((o) => h("div.choice", h("span.choice-mark", o.on ? "✓" : ""), o.label));
  const el = h("div.screen.dialog", h("div.dlg-box.wide", h("div.dlg-title", title), h("div.choices", items)));
  const paint = () => items.forEach((b, j) => b.classList.toggle("focus", i === j));
  paint();
  Screens.push({ el, key(k) {
    if (k === "up") { i = clamp(i - 1, 0, items.length - 1); paint(); }
    else if (k === "down") { i = clamp(i + 1, 0, items.length - 1); paint(); }
    else if (k === "ok") { Screens.pop(); options[i].pick(); }
    else if (k === "back") Screens.pop();
  } });
}

// ---------------------------------------------------------------- ekran klavyesi
const LETTERS = "abcçdefgğhıijklmnoöprsştuüvyzqwx0123456789".split("");
/**
 * Netflix TV tarzı klavye: 6 sütun harf + alt satır (boşluk, sil, temizle).
 * keyboard({value, onChange, digits}) → {el, key(k) → "left"/"right"/"up"/"down" sınır dışına çıkış, get value}
 */
export function keyboard({ value = "", onChange, digits = false, cols = 7 } = {}) {
  const chars = digits ? "123456789.0".split("") : LETTERS;
  const cells = chars.map((c) => ({ label: c, act: () => set(value + c) }));
  const special = digits
    ? [{ label: "Sil", wide: 2, act: () => set(value.slice(0, -1)) }]
    : [{ label: "␣ Boşluk", wide: 2, act: () => set(value + " ") }, { label: "⌫ Sil", wide: 2, act: () => set(value.slice(0, -1)) }, { label: "Temizle", wide: 2, act: () => set("") }];
  const grid = [];
  for (let i = 0; i < cells.length; i += cols) grid.push(cells.slice(i, i + cols));
  grid.push(special);
  let r = 0, c = 0;
  const rowsEl = grid.map((row) => h("div.kb-row", row.map((cell) => (cell.el = h("div.kb-key" + (cell.wide ? ".wide" : ""), cell.label)))));
  const el = h("div.keyboard", rowsEl);
  function set(v) { value = v; onChange && onChange(v); }
  function paint(on = true) { grid.forEach((row, ri) => row.forEach((cell, ci) => cell.el.classList.toggle("focus", on && ri === r && ci === c))); }
  paint(false);
  return {
    el,
    get value() { return value; },
    set value(v) { value = v; },
    focus() { paint(true); },
    blur() { paint(false); },
    key(k) {
      if (k === "left") { if (c === 0) return "left"; c--; }
      else if (k === "right") { if (c >= grid[r].length - 1) return "right"; c++; }
      else if (k === "up") { if (r === 0) return "up"; r--; c = Math.min(c, grid[r].length - 1); }
      else if (k === "down") { if (r >= grid.length - 1) return "down"; r++; c = Math.min(c, grid[r].length - 1); }
      else if (k === "ok") grid[r][c].act();
      paint(true);
      return null;
    },
  };
}

/** Görsel önyükleme (geçişlerde boş kare görünmesin). */
const imgCache = new Map();
export function preload(url) {
  if (!url) return Promise.resolve();
  if (imgCache.has(url)) return imgCache.get(url);
  const p = new Promise((ok) => { const i = new Image(); i.onload = i.onerror = () => ok(); i.src = url; });
  imgCache.set(url, p);
  return p;
}
export const tmdb = (url, size) => (url ? url.replace(/\/t\/p\/[^/]+\//, `/t/p/${size}/`) : null);
export { h, clear };
