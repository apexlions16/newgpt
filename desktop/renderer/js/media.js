// Video kaynakları: HLS (eski içerikler), parçalı doğrudan dosyalar (MKV/MP4), dublajlı sürümler, fragmanlar (4K VP9).

const assetId = (u) => (String(u).match(/\/releases\/assets\/(\d+)/) || [])[1];
export const assetUrl = (u, extra = "") => `/asset/${assetId(u)}${extra}`;
export const hlsUrl = (u) => `/hls/${assetId(u)}`;

/** Parçalı dosya (f.p000, f.p001 …) → tek adres; yerel sunucu birleştirip Range ile sunar. */
export function partsUrl(pf, type) {
  const q = new URLSearchParams();
  (pf.parts || []).forEach((u, i) => { q.append("u", u); q.append("s", String(pf.sizes[i] || 0)); });
  if (type) q.set("type", type);
  return "/parts?" + q.toString();
}

/** <video>'ya kaynak bağlar. Döner: { hls, destroy() } */
export function attach(video, src) {
  const out = { hls: null, destroy() { if (this.hls) { this.hls.destroy(); this.hls = null; } } };
  if (src.kind === "hls") {
    if (window.Hls && window.Hls.isSupported()) {
      const hls = new window.Hls({ maxBufferLength: 60, backBufferLength: 30, enableWorker: true });
      hls.loadSource(src.url);
      hls.attachMedia(video);
      out.hls = hls;
    } else video.src = src.url;
  } else {
    video.src = src.url;
  }
  return out;
}

// ---- fragmanlar

export const allTrailers = (t) => (t.trailers && t.trailers.length ? t.trailers : t.trailer ? [t.trailer] : []);

export function pickTrailer(t, lang) {
  const list = allTrailers(t);
  return list.find((x) => x.lang === lang) || list.find((x) => x.lang === "tr") || t.trailer || list[0] || null;
}

export const trailerKey = (s) => (s.master || s.direct?.video?.parts?.[0] || "") + "|" + (s.lang || "");

let uhdOk = null;
export async function canPlayUhd() {
  if (uhdOk != null) return uhdOk;
  try {
    const r = await navigator.mediaCapabilities.decodingInfo({
      type: "file", video: { contentType: 'video/webm; codecs="vp09.00.51.08"', width: 3840, height: 2160, bitrate: 20e6, framerate: 24 },
    });
    uhdOk = !!(r.supported && r.smooth);
  } catch (_) { uhdOk = false; }
  return uhdOk;
}

/** Fragman kaynağı: 4K VP9 (ekran büyük ve çözücü yeterliyse) > 1080p MP4 > HLS. */
export async function trailerSource(s, wantUhd) {
  if (wantUhd && s.uhd && (await canPlayUhd()) && window.screen.width * devicePixelRatio >= 2560) return { kind: "file", url: partsUrl(s.uhd, "webm"), uhd: true };
  if (s.direct) return { kind: "file", url: partsUrl(s.direct.video, "mp4") };
  return { kind: "hls", url: hlsUrl(s.master) };
}

// ---- film/bölüm

/**
 * Oynatma planı:
 *  versions: dil başına ayrı video (dil değişince aynı saniyeden öbür videoya geçilir)
 *  direct:   tek dosya (MKV: içindeki sesler video.audioTracks ile seçilir); eski "hızlı" yüklemelerde ayrı ses dosyaları
 *  hls:      eski içerikler (hls.js ses grupları)
 */
export function planFor(stream, audioLang) {
  if (stream.versions && stream.versions.length) {
    const v = stream.versions.find((x) => x.lang === audioLang) || stream.versions.find((x) => x.lang === "tr") || stream.versions[0];
    return { mode: "versions", lang: v.lang, src: { kind: "file", url: partsUrl(v.video) } };
  }
  if (stream.direct) {
    return { mode: "direct", src: { kind: "file", url: partsUrl(stream.direct.video) },
      extAudio: (stream.direct.audio || []).filter((a) => a.parts && a.parts.length).map((a) => ({ ...a, url: partsUrl(a, "mp4") })) };
  }
  return { mode: "hls", src: { kind: "hls", url: hlsUrl(stream.master) } };
}

export const subtitleUrl = (s) => assetUrl(s.url, "?sub=1");
