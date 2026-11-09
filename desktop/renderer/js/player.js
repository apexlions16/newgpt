// Oynatıcı (Netflix masaüstü düzeni): alt çubuk, ses/altyazı paneli, bölümler, sonraki bölüm, klavye kısayolları.
import { h, svg, clear } from "./dom.js";
import { Store } from "./store.js";
import { attach, planFor, subtitleUrl } from "./media.js";
import { episodes, findEpisode, nextEpisode, formatClock, languageName, sameLanguage, isSeries } from "./library.js";

const VOL_KEY = "gflix.volume";

export function openPlayer(title, season, episode, positionMs, { onClose } = {}) {
  let item = resolve(title, season, episode);
  if (!item) { alert("Bu içeriğin videosu henüz yüklenmemiş."); return; }

  const video = h("video.pl-video", { playsinline: true, preload: "auto" });
  video.crossOrigin = "anonymous";
  const savedVol = JSON.parse(localStorage.getItem(VOL_KEY) || '{"v":1,"m":false}');
  video.volume = savedVol.v; video.muted = savedVol.m;

  // ---- arayüz
  const backBtn = h("button.pl-back", { title: "Geri (Esc)", onclick: () => close() }, svg("back"));
  const heading = h("div.pl-heading");
  const flash = h("div.pl-flash");
  const spinner = h("div.pl-spinner");
  const err = h("div.pl-error");
  const scrubFill = h("div.pl-scrub-fill");
  const scrubBuf = h("div.pl-scrub-buf");
  const scrubKnob = h("div.pl-scrub-knob");
  const scrubTip = h("div.pl-scrub-tip");
  const scrub = h("div.pl-scrub", h("div.pl-scrub-track", scrubBuf, scrubFill, scrubKnob), scrubTip);
  const remain = h("div.pl-remain");
  const playBtn = h("button.pl-btn", { title: "Oynat/Duraklat (Boşluk)", onclick: () => togglePlay() }, svg("play"));
  const rewBtn = h("button.pl-btn", { title: "10 sn geri (←)", onclick: () => seekBy(-10) }, svg("rew10"));
  const fwdBtn = h("button.pl-btn", { title: "10 sn ileri (→)", onclick: () => seekBy(10) }, svg("fwd10"));
  const volSlider = h("input.pl-vol-slider", { type: "range", min: "0", max: "1", step: "0.02", value: String(video.muted ? 0 : video.volume) });
  const volBtn = h("button.pl-btn", { title: "Ses (M)", onclick: () => { video.muted = !video.muted; } }, svg("volume"));
  const volWrap = h("div.pl-vol", volBtn, h("div.pl-vol-pop", volSlider));
  const titleCenter = h("div.pl-title");
  const nextBtn = h("button.pl-btn", { title: "Sonraki bölüm (N)", onclick: () => playNext() }, svg("next"));
  const epBtn = h("button.pl-btn", { title: "Bölümler", onclick: () => togglePanel("episodes") }, svg("episodes"));
  const subBtn = h("button.pl-btn", { title: "Ses ve Altyazılar", onclick: () => togglePanel("subs") }, svg("subs"));
  const speedBtn = h("button.pl-btn", { title: "Oynatma hızı", onclick: () => togglePanel("speed") }, svg("speed"));
  const fullBtn = h("button.pl-btn", { title: "Tam ekran (F)", onclick: () => toggleFull() }, svg("full"));
  const panel = h("div.pl-panel");
  const nextCard = h("button.pl-nextcard", { onclick: () => playNext() });
  const controls = h("div.pl-controls",
    h("div.pl-scrubrow", scrub, remain),
    h("div.pl-buttons",
      h("div.pl-left", playBtn, rewBtn, fwdBtn, volWrap),
      titleCenter,
      h("div.pl-right", nextBtn, epBtn, subBtn, speedBtn, fullBtn)));
  const root = h("div.player", video, h("div.pl-top", backBtn, heading), flash, spinner, err, nextCard, panel, controls);
  document.body.appendChild(root);
  document.body.classList.add("playing");

  let attached = null, plan = null, extAudio = null, saveTimer = null, hideTimer = null, panelName = null, closed = false;
  const data = Store.data;

  // ---- oynatma
  function resolve(t, s, e) {
    if (!isSeries(t)) return t.stream ? { season: null, episode: null, stream: t.stream, label: null } : null;
    const ref = findEpisode(t, s, e) || episodes(t)[0];
    if (!ref) return null;
    const name = ref.episode.title || `Bölüm ${ref.episode.number}`;
    return { season: ref.season.number, episode: ref.episode.number, stream: ref.episode.stream, label: `B${ref.episode.number}: ${name}`, ref };
  }

  function load(it, pos, autoplay = true) {
    item = it;
    teardown();
    err.textContent = "";
    clear(heading).append(title.title);
    clear(titleCenter).append(h("b", title.title));
    if (it.label) titleCenter.append(h("span", " " + it.label));
    nextBtn.style.display = isSeries(title) && nextEpisode(title, it.season, it.episode) ? "" : "none";
    epBtn.style.display = isSeries(title) ? "" : "none";
    plan = planFor(it.stream, data.audioLang || "tr");
    attached = attach(video, plan.src);
    // altyazılar (ayrı VTT dosyaları)
    [...video.querySelectorAll("track")].forEach((t) => t.remove());
    (it.stream.subtitles || []).forEach((s, i) => {
      video.appendChild(h("track", { kind: "subtitles", src: subtitleUrl(s), srclang: s.lang, label: s.label || languageName(s.lang) + (s.forced ? " (Zorunlu)" : ""), "data-i": String(i), "data-forced": s.forced ? "1" : "" }));
    });
    if (plan.mode === "direct" && plan.extAudio.length) setupExtAudio();
    const start = () => {
      if (pos > 0) video.currentTime = pos / 1000;
      applySubtitlePref();
      if (attached.hls) applyHlsAudioPref();
      if (plan.mode === "direct" && !plan.extAudio.length) applyNativeAudioPref();
      if (autoplay) video.play().catch(() => {});
    };
    if (video.readyState >= 1) start(); else video.addEventListener("loadedmetadata", start, { once: true });
    if (attached.hls) attached.hls.on(window.Hls.Events.MANIFEST_PARSED, () => applyHlsAudioPref());
    if (attached.hls) attached.hls.on(window.Hls.Events.ERROR, (_, d) => { if (d.fatal) showError("Video yüklenemedi (" + d.details + ")"); });
    clearInterval(saveTimer);
    saveTimer = setInterval(saveProgress, 5000);
    nextCard.classList.remove("show");
    renderPanel();
  }

  function teardown() {
    if (attached) attached.destroy();
    attached = null;
    if (extAudio) { extAudio.forEach((a) => { a.el.pause(); a.el.removeAttribute("src"); a.el.load(); }); extAudio = null; }
    video.removeAttribute("src");
    video.load();
  }

  // Eski "hızlı" yüklemeler: ayrı ses dosyaları videoyla birlikte çalınır
  function setupExtAudio() {
    extAudio = plan.extAudio.map((a) => ({ ...a, el: h("audio", { preload: "auto", src: a.url }) }));
    const pick = extAudio.find((a) => sameLanguage(a.lang, data.audioLang)) || extAudio.find((a) => a.default) || extAudio[0];
    extAudio.forEach((a) => (a.active = a === pick));
    video.muted = true;
    const sync = () => extAudio && extAudio.forEach((a) => {
      if (!a.active) { a.el.pause(); return; }
      a.el.volume = savedVolume().v; a.el.muted = savedVolume().m; a.el.playbackRate = video.playbackRate;
      if (Math.abs(a.el.currentTime - video.currentTime) > 0.25) a.el.currentTime = video.currentTime;
      if (!video.paused && a.el.paused) a.el.play().catch(() => {});
      if (video.paused && !a.el.paused) a.el.pause();
    });
    ["play", "pause", "seeked", "ratechange", "waiting", "playing"].forEach((e) => video.addEventListener(e, sync));
    extAudio.timer = setInterval(sync, 1000);
  }

  function savedVolume() { return JSON.parse(localStorage.getItem(VOL_KEY) || '{"v":1,"m":false}'); }

  function saveProgress() {
    if (!video.duration || !isFinite(video.duration) || video.currentTime < 1) return;
    Store.saveProgress({ titleId: title.id, season: item.season, episode: item.episode, positionMs: Math.floor(video.currentTime * 1000), durationMs: Math.floor(video.duration * 1000) });
  }

  function togglePlay() { if (video.paused) video.play().catch(() => {}); else video.pause(); showFlash(video.paused ? "pause" : "play"); }
  function seekBy(sec) { if (!isFinite(video.duration)) return; video.currentTime = Math.max(0, Math.min(video.duration - 0.5, video.currentTime + sec)); showFlash(sec < 0 ? "rew10" : "fwd10"); poke(); }
  function toggleFull() { if (document.fullscreenElement) document.exitFullscreen(); else root.requestFullscreen().catch(() => {}); }
  function showFlash(icon) { clear(flash).append(svg(icon)); flash.classList.remove("go"); void flash.offsetWidth; flash.classList.add("go"); }
  function showError(msg) { err.textContent = msg; }

  function playNext() {
    const nx = nextEpisode(title, item.season, item.episode);
    if (!nx) return;
    saveProgress();
    if (video.duration) Store.saveProgress({ titleId: title.id, season: item.season, episode: item.episode, positionMs: Math.floor(video.duration * 1000), durationMs: Math.floor(video.duration * 1000) });
    load(resolve(title, nx.season.number, nx.episode.number), 0);
  }

  // ---- ses ve altyazı tercihleri
  function applySubtitlePref() {
    const want = data.subtitleLang;
    const tracks = [...video.textTracks];
    tracks.forEach((t) => (t.mode = "disabled"));
    const els = [...video.querySelectorAll("track")];
    let chosen = want ? els.find((e) => sameLanguage(e.srclang, want) && !e.dataset.forced) : null;
    // Altyazı kapalıyken, ses diline uygun "zorunlu" altyazı varsa göster (Netflix gibi)
    if (!chosen) chosen = els.find((e) => e.dataset.forced && sameLanguage(e.srclang, currentAudioLang() || "tr"));
    if (chosen) chosen.track.mode = "showing";
  }

  function currentAudioLang() {
    if (plan?.mode === "versions") return plan.lang;
    return data.audioLang;
  }

  function applyHlsAudioPref() {
    const hls = attached?.hls; if (!hls) return;
    const want = data.audioLang || "tr";
    const i = hls.audioTracks.findIndex((t) => sameLanguage(t.lang, want));
    if (i >= 0 && hls.audioTrack !== i) hls.audioTrack = i;
    renderPanel();
  }

  function applyNativeAudioPref() {
    const at = video.audioTracks; if (!at || at.length < 2) return;
    const want = data.audioLang || "tr";
    let idx = [...at].findIndex((t) => sameLanguage(t.language, want));
    if (idx < 0) idx = 0;
    for (let i = 0; i < at.length; i++) at[i].enabled = i === idx;
    renderPanel();
  }

  function audioOptions() {
    if (!plan) return [];
    if (plan.mode === "versions") return item.stream.versions.map((v) => ({ label: v.label || languageName(v.lang) + (sameLanguage(v.lang, title.originalLanguage) ? " [Orijinal]" : ""), lang: v.lang, on: v.lang === plan.lang, pick: () => switchVersion(v.lang) }));
    if (attached?.hls) return attached.hls.audioTracks.map((t, i) => ({ label: (t.lang ? languageName(t.lang) : t.name) + (sameLanguage(t.lang, title.originalLanguage) ? " [Orijinal]" : ""), lang: t.lang, on: attached.hls.audioTrack === i, pick: () => { attached.hls.audioTrack = i; remember(t.lang); } }));
    if (extAudio) return extAudio.map((a) => ({ label: a.label || languageName(a.lang), lang: a.lang, on: a.active, pick: () => { extAudio.forEach((x) => (x.active = x === a)); video.dispatchEvent(new Event("seeked")); remember(a.lang); } }));
    const at = video.audioTracks;
    if (at && at.length) return [...at].map((t, i) => ({ label: (t.language ? languageName(t.language) : t.label || `Ses ${i + 1}`) + (sameLanguage(t.language, title.originalLanguage) ? " [Orijinal]" : ""), lang: t.language, on: t.enabled, pick: () => { for (let k = 0; k < at.length; k++) at[k].enabled = k === i; remember(t.language); } }));
    return (item.stream.audio || []).slice(0, 1).map((l) => ({ label: languageName(l), lang: l, on: true, pick: () => {} }));
  }

  function remember(lang) { if (lang) { data.audioLang = lang; Store.setLanguages(lang, undefined); } renderPanel(); }

  /** Dublajlı sürüm: öbür dildeki videoya aynı saniyeden geç. */
  function switchVersion(lang) {
    if (plan.lang === lang) return;
    const pos = video.currentTime * 1000, wasPlaying = !video.paused;
    remember(lang);
    load(item, pos, wasPlaying);
  }

  function subtitleOptions() {
    const els = [...video.querySelectorAll("track")];
    const anyOn = els.some((e) => e.track.mode === "showing" && !e.dataset.forced);
    return [{ label: "Kapalı", on: !anyOn, pick: () => { data.subtitleLang = null; Store.setLanguages(undefined, null); applySubtitlePref(); renderPanel(); } }]
      .concat(els.filter((e) => !e.dataset.forced).map((e) => ({ label: e.label, on: e.track.mode === "showing", pick: () => { data.subtitleLang = e.srclang; Store.setLanguages(undefined, e.srclang); els.forEach((x) => (x.track.mode = "disabled")); e.track.mode = "showing"; renderPanel(); } })));
  }

  // ---- paneller
  function togglePanel(name) { panelName = panelName === name ? null : name; renderPanel(); poke(); }

  function renderPanel() {
    clear(panel);
    panel.className = "pl-panel" + (panelName ? " open " + panelName : "");
    if (!panelName) return;
    if (panelName === "subs") {
      const col = (head, opts) => h("div.pl-col", h("h4", head), opts.map((o) => h("button.pl-opt" + (o.on ? ".on" : ""), { onclick: o.pick }, o.on ? svg("check") : h("span.ic"), o.label)));
      panel.append(col("Ses", audioOptions()), col("Altyazılar", subtitleOptions()));
    } else if (panelName === "speed") {
      const rates = [0.5, 0.75, 1, 1.25, 1.5];
      panel.append(h("h4", "Oynatma hızı"), h("div.pl-speeds", rates.map((r) => h("button.pl-speed" + (video.playbackRate === r ? ".on" : ""), { onclick: () => { video.playbackRate = r; renderPanel(); } }, h("i"), r === 1 ? "1x (Normal)" : r + "x"))));
    } else if (panelName === "episodes") {
      const seasons = [...new Set(episodes(title).map((r) => r.season.number))];
      let shown = panel.dataset.season ? Number(panel.dataset.season) : item.season;
      const list = h("div.pl-eplist");
      const fill = () => {
        clear(list);
        episodes(title).filter((r) => r.season.number === shown).forEach((r) => {
          const cur = r.season.number === item.season && r.episode.number === item.episode;
          const p = data.progress[`${title.id}/${r.season.number}/${r.episode.number}`];
          list.append(h("button.pl-ep" + (cur ? ".cur" : ""), { onclick: () => { saveProgress(); panelName = null; load(resolve(title, r.season.number, r.episode.number), p && p.positionMs < p.durationMs * 0.95 ? p.positionMs : 0); } },
            h("span.n", String(r.episode.number)), h("span.t", r.episode.title || `Bölüm ${r.episode.number}`),
            p ? h("span.bar", h("i", { style: { width: Math.min(100, (p.positionMs / p.durationMs) * 100) + "%" } })) : null));
        });
      };
      const sel = h("select.pl-season", { onchange: (e) => { shown = Number(e.target.value); panel.dataset.season = String(shown); fill(); } },
        seasons.map((s) => h("option", { value: String(s), selected: s === shown ? "selected" : null }, `${s}. Sezon`)));
      panel.append(h("div.pl-ephead", seasons.length > 1 ? sel : h("h4", `${shown}. Sezon`)), list);
      fill();
    }
  }

  // ---- olaylar
  function fmtRemain() {
    if (!isFinite(video.duration)) return "";
    return formatClock((video.duration - video.currentTime) * 1000);
  }

  video.addEventListener("timeupdate", () => {
    const d = video.duration;
    if (!isFinite(d) || !d) return;
    const f = video.currentTime / d;
    scrubFill.style.width = f * 100 + "%";
    scrubKnob.style.left = f * 100 + "%";
    remain.textContent = fmtRemain();
    const nx = isSeries(title) && nextEpisode(title, item.season, item.episode);
    if (nx && d - video.currentTime < 30) {
      if (!nextCard.classList.contains("show")) { clear(nextCard).append(svg("next"), h("span", "Sonraki Bölüm")); nextCard.classList.add("show"); }
    } else nextCard.classList.remove("show");
  });
  video.addEventListener("progress", () => {
    const d = video.duration; if (!isFinite(d) || !d || !video.buffered.length) return;
    let end = 0;
    for (let i = 0; i < video.buffered.length; i++) if (video.buffered.start(i) <= video.currentTime + 1) end = Math.max(end, video.buffered.end(i));
    scrubBuf.style.width = (end / d) * 100 + "%";
  });
  video.addEventListener("play", () => { clear(playBtn).append(svg("pause")); poke(); });
  video.addEventListener("pause", () => { clear(playBtn).append(svg("play")); poke(); saveProgress(); });
  video.addEventListener("waiting", () => spinner.classList.add("on"));
  ["playing", "canplay", "seeked"].forEach((e) => video.addEventListener(e, () => spinner.classList.remove("on")));
  video.addEventListener("volumechange", () => {
    clear(volBtn).append(svg(video.muted || video.volume === 0 ? "mute" : "volume"));
    volSlider.value = String(video.muted ? 0 : video.volume);
    if (!extAudio) localStorage.setItem(VOL_KEY, JSON.stringify({ v: video.volume, m: video.muted }));
  });
  volSlider.addEventListener("input", () => {
    const v = Number(volSlider.value);
    if (extAudio) { localStorage.setItem(VOL_KEY, JSON.stringify({ v, m: v === 0 })); video.dispatchEvent(new Event("seeked")); }
    else { video.volume = v; video.muted = v === 0; }
  });
  video.addEventListener("ended", () => {
    saveProgress();
    if (isSeries(title) && nextEpisode(title, item.season, item.episode)) playNext(); else close();
  });
  video.addEventListener("error", () => { if (!attached?.hls) showError("Video oynatılamadı. İnternet bağlantını ya da erişim anahtarını kontrol et."); });
  video.addEventListener("click", () => { if (panelName) { panelName = null; renderPanel(); } else togglePlay(); });
  video.addEventListener("dblclick", () => toggleFull());

  // sürükleyerek sarma
  const posAt = (e) => { const r = scrub.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); };
  let dragging = false;
  scrub.addEventListener("pointerdown", (e) => { dragging = true; scrub.setPointerCapture(e.pointerId); seekTo(posAt(e)); });
  scrub.addEventListener("pointermove", (e) => {
    const f = posAt(e);
    scrubTip.style.left = f * 100 + "%";
    scrubTip.textContent = isFinite(video.duration) ? formatClock(f * video.duration * 1000) : "";
    if (dragging) seekTo(f);
  });
  scrub.addEventListener("pointerup", () => { dragging = false; });
  function seekTo(f) { if (isFinite(video.duration)) { video.currentTime = f * video.duration; scrubFill.style.width = f * 100 + "%"; scrubKnob.style.left = f * 100 + "%"; } }

  // kontrolleri gizle/göster
  function poke() {
    root.classList.add("ui");
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { if (!video.paused && !panelName) root.classList.remove("ui"); }, 3000);
  }
  root.addEventListener("mousemove", poke);
  document.addEventListener("fullscreenchange", onFs);
  function onFs() { clear(fullBtn).append(svg(document.fullscreenElement ? "unfull" : "full")); }

  function onKey(e) {
    if (e.target.tagName === "INPUT" && e.target.type !== "range") return;
    const k = e.key;
    if (k === " " || k === "k" || k === "K") { e.preventDefault(); togglePlay(); }
    else if (k === "ArrowLeft") { e.preventDefault(); seekBy(-10); }
    else if (k === "ArrowRight") { e.preventDefault(); seekBy(10); }
    else if (k === "ArrowUp") { e.preventDefault(); video.muted = false; video.volume = Math.min(1, video.volume + 0.1); }
    else if (k === "ArrowDown") { e.preventDefault(); video.volume = Math.max(0, video.volume - 0.1); }
    else if (k === "f" || k === "F") toggleFull();
    else if (k === "m" || k === "M") video.muted = !video.muted;
    else if (k === "n" || k === "N") playNext();
    else if (k === "Escape") { if (panelName) { panelName = null; renderPanel(); } else if (!document.fullscreenElement) close(); }
    poke();
  }
  document.addEventListener("keydown", onKey);

  function close() {
    if (closed) return;
    closed = true;
    saveProgress();
    clearInterval(saveTimer);
    if (extAudio?.timer) clearInterval(extAudio.timer);
    document.removeEventListener("keydown", onKey);
    document.removeEventListener("fullscreenchange", onFs);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    teardown();
    root.remove();
    document.body.classList.remove("playing");
    onClose && onClose();
  }

  load(item, positionMs || 0);
  poke();
  return { close, video };
}
