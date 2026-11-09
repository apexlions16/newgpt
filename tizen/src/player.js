// TV oynatıcı (Netflix TV düzeni): alt çubukta sarma, ▲ ile seçenekler (Bölümler, Ses ve Altyazı, Sonraki Bölüm),
// sonraki bölüm geri sayımı, kaldığı yerden devam. Tizen'de Samsung AVPlay (MKV, çok sesli), tarayıcıda HTML5.
import { svg } from "../../desktop/renderer/js/dom.js";
import { Store } from "../../desktop/renderer/js/store.js";
import * as L from "../../desktop/renderer/js/library.js";
import { planFor, subtitleUrl } from "../../desktop/renderer/js/media.js";
import { Net, createEngine, loadSubtitles, cueAt, screenSaver } from "./core.js";
import { Screens, h, clear, clamp, tmdb } from "./ui.js";
import { canStart, queueOverlay } from "./queue.js";

export function openPlayer(title, season, episode, positionMs) {
  const data = Store.data;
  let item = resolve(season, episode);
  if (!item) return;

  const video = h("video.pl-video", { playsinline: true });
  const engine = createEngine(video);
  const top = h("div.pl-top", h("div.pl-title"));
  const subs = h("div.pl-subs" + ({ s: ".small", l: ".large", xl: ".xlarge" }[localStorage.getItem("gflix.tv.subsize")] || ""));
  const spinner = h("div.pl-spinner");
  const err = h("div.pl-error");
  const fill = h("div.pl-fill"), knob = h("div.pl-knob"), tip = h("div.pl-tip");
  const bar = h("div.pl-bar", h("div.pl-track", fill, knob), tip);
  const remain = h("div.pl-remain");
  const playIc = h("div.pl-state");
  const optWrap = h("div.pl-opts");
  const panel = h("div.pl-panel");
  const nextCard = h("div.pl-next");
  const bottom = h("div.pl-bottom", h("div.pl-barrow", playIc, bar, remain), optWrap);
  const el = h("div.screen.player", video, subs, top, spinner, err, nextCard, panel, bottom);

  let plan = null, cues = null, subLang = null, hideTimer = null, saveTimer = null, area = "bar", oi = 0, opts = [];
  let seekTarget = null, seekTimer = null, seekStep = 10000, lastSeekKey = 0, panelState = null, nextState = null, closed = false;

  function resolve(s, e) {
    if (!L.isSeries(title)) return title.stream ? { season: null, episode: null, stream: title.stream, label: "" } : null;
    const ref = L.findEpisode(title, s, e) || L.episodes(title)[0];
    if (!ref) return null;
    return { season: ref.season.number, episode: ref.episode.number, stream: ref.episode.stream, label: `S${ref.season.number}:B${ref.episode.number} ${ref.episode.title || ""}`, ref };
  }

  // ---------------------------------------------------------------- yükleme
  function load(it, pos, fresh) {
    item = it;
    err.textContent = ""; err.classList.remove("on");
    clear(top.firstChild).append(h("b", title.title), it.label ? h("span", it.label) : null);
    hideNext();
    if (it.stream && it.stream.live) return loadLive(it, pos, fresh);
    plan = planFor(it.stream, data.audioLang || "tr");
    engine.open(Net.url(plan.src.url), pos);
    setupSubs(data.subtitleLang);
    buildOpts();
    showUi();
  }
  // Linkten anlık yayın: bilgisayar siteden o anki linki çözer (birkaç saniye), HLS'i kendi üzerinden aktarır
  let liveSubs = [];
  const loadTok = { n: 0 };
  async function loadLive(it, pos, fresh) {
    const my = ++loadTok.n;
    plan = { mode: "live" };
    liveSubs = [];
    spinner.classList.add("on");
    clear(err).append(h("b", "Bilgisayar yayını hazırlıyor…"), h("span", "Siteden taze link alınıyor (5–15 sn)"));
    err.classList.add("on", "soft");
    let r;
    try {
      r = await Net.post("/api/play", { id: title.id, season: it.season, episode: it.episode, fresh: !!fresh, ...(it.session ? { session: it.session } : {}) });
    } catch (e) {
      if (closed || my !== loadTok.n) return;
      spinner.classList.remove("on");
      err.classList.remove("soft");
      clear(err).append(h("b", "Yayın açılamadı"), h("span", e.needsSource ? "Bu yapımın sitedeki sayfası bilgisayarda kayıtlı değil (telefonda Sayfadan Yakala)." : e.message), h("small", "OK: tekrar dene · Geri: çık"));
      return;
    }
    if (closed || my !== loadTok.n) return;
    err.classList.remove("on", "soft");
    liveSubs = (r.subs || []).map((s) => ({ lang: s.lang, label: s.label, forced: s.forced, url: s.url }));
    engine.open(r.hls, pos);
    setupSubs(data.subtitleLang);
    buildOpts();
    showUi();
  }
  engine.on("buffering", (b) => spinner.classList.toggle("on", !!b));
  engine.on("ready", () => { applyAudioPref(); paintBar(); });
  engine.on("playing", () => { paintState(); screenSaver(false); });
  engine.on("paused", () => { paintState(); showUi(true); });
  engine.on("time", () => { if (seekTarget == null) paintBar(); renderSubs(); checkNext(); });
  engine.on("ended", () => onEnded());
  let queued = null;
  engine.on("error", async (m) => {
    spinner.classList.remove("on");
    // Yoğunluk mu? Öyleyse hata değil "Sıradasın": sıra gelince kaldığı yerden
    const r = await canStart();
    if (!r.ok && !queued) {
      const pos = engine.time();
      save();
      queued = queueOverlay(el, title.title, true, () => { queued = null; load(item, pos); });
      return;
    }
    err.classList.add("on"); clear(err).append(h("b", "Video oynatılamadı"), h("span", `Bilgisayar açık ve aynı ağda mı? (${m})`), h("small", "OK: tekrar dene · Geri: çık"));
  });

  function applyAudioPref() {
    if (plan.mode === "versions") return;
    const want = data.audioLang; if (!want) return;
    const tr = engine.audioTracks();
    const m = tr.find((t) => L.sameLanguage(t.lang, want));
    if (m && !m.on) engine.selectAudio(m.id);
  }
  function currentAudioLang() {
    if (plan.mode === "versions") return plan.lang;
    const on = engine.audioTracks().find((t) => t.on);
    return on ? on.lang : data.audioLang || "tr";
  }

  // ---------------------------------------------------------------- altyazılar
  async function setupSubs(lang) {
    cues = null; subLang = null; subs.textContent = "";
    const live = plan && plan.mode === "live";
    const list = live ? liveSubs : item.stream.subtitles || [];
    let pick = lang ? list.find((s) => L.sameLanguage(s.lang, lang) && !s.forced) : null;
    if (!pick) pick = list.find((s) => s.forced && L.sameLanguage(s.lang, data.audioLang || "tr"));
    if (!pick) return;
    subLang = pick.forced ? null : pick.lang;
    try { const c = await loadSubtitles(live ? pick.url : Net.url(subtitleUrl(pick))); if (!closed) cues = c; } catch (_) {}
  }
  function renderSubs() {
    if (!cues) return;
    const txt = cueAt(cues, engine.time());
    if (subs._t !== txt) { subs._t = txt; subs.innerHTML = txt ? txt.split("\n").map((l) => `<span>${l.replace(/&/g, "&amp;").replace(/<(?!\/?[ib]>)/g, "&lt;")}</span>`).join("<br>") : ""; }
  }

  // ---------------------------------------------------------------- arayüz
  function showUi(stay) {
    el.classList.add("ui");
    clearTimeout(hideTimer);
    if (!stay && !engine.paused) hideTimer = setTimeout(() => { if (!panelState && seekTarget == null) { el.classList.remove("ui"); area = "bar"; paintOpts(); } }, 4500);
  }
  function paintState() { clear(playIc).append(svg(engine.paused ? "play" : "pause")); }
  function paintBar() {
    const d = engine.duration(), t = seekTarget != null ? seekTarget : engine.time();
    const f = d ? clamp(t / d, 0, 1) : 0;
    fill.style.width = `${f * 100}%`;
    knob.style.left = `${f * 100}%`;
    tip.style.left = `${f * 100}%`;
    tip.textContent = L.formatClock(t);
    tip.classList.toggle("on", seekTarget != null);
    remain.textContent = d ? L.formatClock(Math.max(0, d - t)) : "";
    bar.classList.toggle("focus", area === "bar");
  }
  function buildOpts() {
    opts = [];
    if (L.isSeries(title)) opts.push({ icon: "episodes", label: "Bölümler", act: () => openPanel("episodes") });
    opts.push({ icon: "subs", label: "Ses ve Altyazı", act: () => openPanel("subs") });
    opts.push({ icon: "replay", label: "Baştan Başlat", act: () => { engine.seek(0); showUi(); } });
    if (L.isSeries(title) && L.nextEpisode(title, item.season, item.episode)) opts.push({ icon: "next", label: "Sonraki Bölüm", act: () => playNext() });
    clear(optWrap);
    opts.forEach((o) => optWrap.appendChild((o.el = h("div.pl-opt", svg(o.icon), h("span", o.label)))));
    oi = clamp(oi, 0, opts.length - 1);
    paintOpts();
  }
  function paintOpts() { opts.forEach((o, j) => o.el.classList.toggle("focus", area === "opts" && j === oi)); bar.classList.toggle("focus", area === "bar"); }

  // ---------------------------------------------------------------- sarma (basılı tuttukça hızlanır, bırakınca atlar)
  function seekBy(dir) {
    const now = Date.now();
    seekStep = now - lastSeekKey < 450 ? Math.min(seekStep * 1.25, 120000) : 10000;
    lastSeekKey = now;
    const d = engine.duration() || Infinity;
    seekTarget = clamp((seekTarget != null ? seekTarget : engine.time()) + dir * seekStep, 0, d - 1000);
    paintBar(); showUi();
    clearTimeout(seekTimer);
    seekTimer = setTimeout(commitSeek, 700);
  }
  function commitSeek() {
    if (seekTarget == null) return;
    engine.seek(seekTarget);
    seekTarget = null;
    if (engine.paused) engine.play();
    paintBar(); showUi();
  }

  // ---------------------------------------------------------------- paneller
  function openPanel(kind) {
    clearTimeout(hideTimer);
    el.classList.add("panel-open");
    if (kind === "subs") {
      const audio = audioOptions();
      const subsList = [{ label: "Kapalı", on: !subLang, pick: () => { Store.setLanguages(undefined, null); setupSubs(null); } }]
        .concat((plan && plan.mode === "live" ? liveSubs : item.stream.subtitles || []).filter((s) => !s.forced).map((s) => ({ label: s.label || L.languageName(s.lang), on: L.sameLanguage(subLang, s.lang), pick: () => { Store.setLanguages(undefined, s.lang); setupSubs(s.lang); } })));
      panelState = { kind, cols: [audio, subsList], c: 0, i: [Math.max(0, audio.findIndex((x) => x.on)), Math.max(0, subsList.findIndex((x) => x.on))] };
    } else {
      const seasons = [...(title.seasons || [])].filter((s) => (s.episodes || []).some((e) => e.stream)).sort((a, b) => a.number - b.number);
      const si = Math.max(0, seasons.findIndex((s) => s.number === item.season));
      panelState = { kind, seasons, si, ei: 0, area: "eps" };
      const eps = epsOf(seasons[si]); panelState.ei = Math.max(0, eps.findIndex((e) => e.number === item.episode));
    }
    renderPanel();
  }
  const epsOf = (s) => s.episodes.filter((e) => e.stream).sort((a, b) => a.number - b.number);
  function audioOptions() {
    const orig = (lang) => (L.sameLanguage(lang, title.originalLanguage) ? " [Orijinal]" : "");
    if (plan.mode === "versions") return item.stream.versions.map((v) => ({ label: (v.label || L.languageName(v.lang)) + orig(v.lang), on: v.lang === plan.lang, pick: () => switchVersion(v.lang) }));
    const tr = engine.audioTracks();
    if (!tr.length) return (item.stream.audio || []).map((a, j) => ({ label: L.languageName(a) + orig(a), on: j === 0, pick: () => {} }));
    return tr.map((t, j) => ({ label: (t.lang ? L.languageName(t.lang) : t.label || `Ses ${j + 1}`) + orig(t.lang), on: t.on, pick: () => { engine.selectAudio(t.id); if (t.lang) Store.setLanguages(t.lang, undefined); } }));
  }
  function switchVersion(lang) {
    Store.setLanguages(lang, undefined);
    const pos = engine.time();
    plan = planFor(item.stream, lang);
    engine.open(Net.url(plan.src.url), pos);
  }
  function renderPanel() {
    clear(panel);
    const p = panelState;
    if (!p) { el.classList.remove("panel-open"); return; }
    if (p.kind === "subs") {
      panel.className = "pl-panel subs";
      ["Ses", "Altyazılar"].forEach((name, c) => {
        panel.appendChild(h("div.pl-col", h("div.pl-col-title", name), p.cols[c].map((o, j) =>
          h("div.pl-choice" + (p.c === c && p.i[c] === j ? ".focus" : "") + (o.on ? ".on" : ""), h("span.mark", o.on ? "✓" : ""), o.label))));
      });
    } else {
      panel.className = "pl-panel eps";
      const s = p.seasons[p.si];
      const eps = epsOf(s);
      panel.appendChild(h("div.pl-eps-head",
        p.seasons.length > 1 ? h("div.pl-season" + (p.area === "season" ? ".focus" : ""), svg("left"), `${s.number}. Sezon`, svg("right")) : h("div.pl-season", `${s.number}. Sezon`)));
      const track = h("div.pl-eps-track", eps.map((e, j) => {
        const pr = data.progress[L.progressKey(title.id, s.number, e.number)];
        return h("div.pl-ep" + (p.area === "eps" && p.ei === j ? ".focus" : "") + (s.number === item.season && e.number === item.episode ? ".current" : ""),
          h("div.pl-ep-img", { style: { backgroundImage: `url("${tmdb(e.still || title.backdrop, "w500")}")` } }, pr ? h("div.ep-prog", h("i", { style: { width: `${Math.round(require0(pr))}%` } })) : null),
          h("div.pl-ep-name", `${e.number}. ${e.title || "Bölüm " + e.number}`), h("div.pl-ep-ov", e.overview || ""));
      }));
      track.style.transform = `translateX(${-Math.max(0, p.ei - 1) * 440}px)`;
      panel.appendChild(track);
    }
  }
  const require0 = (pr) => (pr.durationMs ? Math.min(100, (pr.positionMs / pr.durationMs) * 100) : 0);
  function closePanel() { panelState = null; area = "bar"; renderPanel(); paintOpts(); showUi(); }
  function panelKey(k) {
    const p = panelState;
    if (k === "back") return closePanel();
    if (p.kind === "subs") {
      if (k === "left") p.c = 0; else if (k === "right") p.c = 1;
      else if (k === "up") p.i[p.c] = clamp(p.i[p.c] - 1, 0, p.cols[p.c].length - 1);
      else if (k === "down") p.i[p.c] = clamp(p.i[p.c] + 1, 0, p.cols[p.c].length - 1);
      else if (k === "ok") { const o = p.cols[p.c][p.i[p.c]]; if (o) o.pick(); setTimeout(() => { if (panelState === p) { openPanel("subs"); panelState.c = p.c; renderPanel(); } }, 400); }
      p.i[p.c] = clamp(p.i[p.c], 0, Math.max(0, p.cols[p.c].length - 1));
      return renderPanel();
    }
    const eps = epsOf(p.seasons[p.si]);
    if (p.area === "season") {
      if (k === "left") { p.si = clamp(p.si - 1, 0, p.seasons.length - 1); p.ei = 0; }
      else if (k === "right") { p.si = clamp(p.si + 1, 0, p.seasons.length - 1); p.ei = 0; }
      else if (k === "down" || k === "ok") p.area = "eps";
    } else {
      if (k === "left") p.ei = clamp(p.ei - 1, 0, eps.length - 1);
      else if (k === "right") p.ei = clamp(p.ei + 1, 0, eps.length - 1);
      else if (k === "up" && p.seasons.length > 1) p.area = "season";
      else if (k === "ok") {
        const s = p.seasons[p.si], e = eps[p.ei];
        save(); panelState = null; renderPanel();
        const pr = data.progress[L.progressKey(title.id, s.number, e.number)];
        return load(resolve(s.number, e.number), pr && pr.durationMs && pr.positionMs < pr.durationMs * 0.95 ? pr.positionMs : 0);
      }
    }
    renderPanel();
  }

  // ---------------------------------------------------------------- sonraki bölüm
  function checkNext() {
    if (!L.isSeries(title) || nextState || closed) return;
    const d = engine.duration(), t = engine.time();
    if (d > 120000 && d - t < 25000 && L.nextEpisode(title, item.season, item.episode)) showNext(Math.ceil((d - t) / 1000));
  }
  function showNext(secs) {
    const nx = L.nextEpisode(title, item.season, item.episode);
    if (!nx) return;
    nextState = { left: Math.max(5, Math.min(15, secs)), nx };
    const draw = () => { clear(nextCard).append(
      h("div.pl-next-img", { style: { backgroundImage: `url("${tmdb(nx.episode.still || title.backdrop, "w500")}")` } }),
      h("div.pl-next-text", h("small", "Sonraki Bölüm"), h("b", `B${nx.episode.number}: ${nx.episode.title || ""}`), h("div.pl-next-btn", svg("play"), `${nextState.left} sn içinde oynat`)),
    ); };
    draw();
    nextCard.classList.add("on");
    nextState.timer = setInterval(() => { if (!nextState) return; nextState.left--; if (nextState.left <= 0) playNext(); else draw(); }, 1000);
  }
  function hideNext() { if (nextState) clearInterval(nextState.timer); nextState = null; nextCard.classList.remove("on"); }
  function playNext() {
    const nx = L.nextEpisode(title, item.season, item.episode);
    if (!nx) return;
    save(true);
    hideNext();
    load(resolve(nx.season.number, nx.episode.number), 0);
  }
  function onEnded() {
    save(true);
    if (L.isSeries(title) && L.nextEpisode(title, item.season, item.episode)) return playNext();
    close();
  }

  // ---------------------------------------------------------------- ilerleme
  function save(finished) {
    const d = engine.duration();
    if (!d) return;
    const pos = finished ? d : engine.time();
    if (pos < 3000 && !finished) return;
    Store.saveProgress({ titleId: title.id, season: item.season, episode: item.episode, positionMs: Math.floor(pos), durationMs: Math.floor(d) });
  }
  function close() {
    if (closed) return;
    closed = true;
    save();
    clearInterval(saveTimer); clearTimeout(hideTimer); clearTimeout(seekTimer); hideNext();
    engine.destroy();
    screenSaver(true);
    document.body.classList.remove("playing");
    Screens.pop();
  }

  // ---------------------------------------------------------------- tuşlar
  function key(k, repeat) {
    if (queued) { if (k === "back") { queued(); queued = null; close(); } return; }
    if (err.classList.contains("on")) {
      if (err.classList.contains("soft")) { if (k === "back") close(); return; }   // yayın hazırlanıyor
      if (k === "ok") { const pos = engine.time(); load(item, pos, true); } else if (k === "back") close();
      return;
    }
    if (panelState) return panelKey(k);
    if (nextState && (k === "ok" || k === "back")) { if (k === "ok") playNext(); else hideNext(); return; }
    const visible = el.classList.contains("ui");
    if (k === "back") { if (seekTarget != null) { seekTarget = null; clearTimeout(seekTimer); paintBar(); return; } return close(); }
    if (k === "stop") return close();
    if (k === "play") { engine.play(); return showUi(); }
    if (k === "pause") { engine.pause(); return; }
    if (k === "playpause") { engine.paused ? engine.play() : engine.pause(); return showUi(); }
    if (k === "ff") return seekBy(+1);
    if (k === "rw") return seekBy(-1);
    if (!visible) { showUi(); if (k === "left" || k === "right") return seekBy(k === "right" ? 1 : -1); if (k === "up") { area = "opts"; paintOpts(); } return; }
    showUi();
    if (area === "bar") {
      if (k === "left") seekBy(-1);
      else if (k === "right") seekBy(+1);
      else if (k === "ok") { if (seekTarget != null) commitSeek(); else engine.paused ? engine.play() : engine.pause(); }
      else if (k === "down" || k === "up") { area = "opts"; paintOpts(); }
    } else {
      if (k === "left") oi = clamp(oi - 1, 0, opts.length - 1);
      else if (k === "right") oi = clamp(oi + 1, 0, opts.length - 1);
      else if (k === "up" || k === "down") area = "bar";
      else if (k === "ok") return opts[oi].act();
      paintOpts();
    }
  }

  document.body.classList.add("playing");
  Screens.push({ el, key, player: true, close, visibility(v) { if (!v) { save(); engine.pause(); } }, destroy() {} });
  paintState();
  load(item, positionMs);
  saveTimer = setInterval(() => { if (!engine.paused) save(); }, 5000);
}
