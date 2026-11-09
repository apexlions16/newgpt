// Küçük DOM yardımcıları ve simgeler (kütüphanesiz).

/** h("div.kart#x", {onclick}, çocuklar…) */
export function h(tag, attrs, ...children) {
  const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(tag);
  const el = document.createElement(m[1] || "div");
  (m[2].match(/[.#][\w-]+/g) || []).forEach((p) => (p[0] === "." ? el.classList.add(p.slice(1)) : (el.id = p.slice(1))));
  if (attrs && (typeof attrs !== "object" || attrs instanceof Node || Array.isArray(attrs))) { children.unshift(attrs); attrs = null; }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k === "html") el.innerHTML = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

export function svg(name, cls = "ic") {
  const span = document.createElement("span");
  span.className = cls;
  span.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ""}</svg>`;
  return span;
}

const ICONS = {
  play: '<path d="M6 4l15 8-15 8z" fill="currentColor"/>',
  pause: '<path d="M6 4h4v16H6zM14 4h4v16h-4z" fill="currentColor"/>',
  plus: '<path d="M12 4v16M4 12h16" stroke="currentColor" stroke-width="2" fill="none"/>',
  check: '<path d="M4 12.5l5 5L20 6.5" stroke="currentColor" stroke-width="2.2" fill="none"/>',
  like: '<path d="M7 10v10H3V10zm2 10h8.6a2 2 0 0 0 2-1.6l1.3-6.4a2 2 0 0 0-2-2.4H14l.8-3.8a1.6 1.6 0 0 0-2.9-1.2L9 9.5z" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linejoin="round"/>',
  liked: '<path d="M7 10v10H3V10zm2 10h8.6a2 2 0 0 0 2-1.6l1.3-6.4a2 2 0 0 0-2-2.4H14l.8-3.8a1.6 1.6 0 0 0-2.9-1.2L9 9.5z" fill="currentColor"/>',
  down: '<path d="M5 9l7 7 7-7" stroke="currentColor" stroke-width="2.2" fill="none"/>',
  left: '<path d="M15 4l-8 8 8 8" stroke="currentColor" stroke-width="2.4" fill="none"/>',
  right: '<path d="M9 4l8 8-8 8" stroke="currentColor" stroke-width="2.4" fill="none"/>',
  info: '<circle cx="12" cy="12" r="9.2" stroke="currentColor" stroke-width="2" fill="none"/><path d="M12 10.5v6.5M12 7v1.6" stroke="currentColor" stroke-width="2.2"/>',
  close: '<path d="M5 5l14 14M19 5L5 19" stroke="currentColor" stroke-width="2.2"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="2.2" fill="none"/><path d="M15.5 15.5L21 21" stroke="currentColor" stroke-width="2.4"/>',
  volume: '<path d="M3 9h4l5-4v14l-5-4H3z" fill="currentColor"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12" stroke="currentColor" stroke-width="1.8" fill="none"/>',
  mute: '<path d="M3 9h4l5-4v14l-5-4H3z" fill="currentColor"/><path d="M16 9l5 6M21 9l-5 6" stroke="currentColor" stroke-width="1.9"/>',
  replay: '<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3" stroke="currentColor" stroke-width="2" fill="none"/><path d="M4 3.5v4.2h4.2" stroke="currentColor" stroke-width="2" fill="none"/>',
  back: '<path d="M20 12H5M11 5l-7 7 7 7" stroke="currentColor" stroke-width="2.2" fill="none"/>',
  rew10: '<path d="M5 12a7 7 0 1 0 2.1-5" stroke="currentColor" stroke-width="1.9" fill="none"/><path d="M4.5 3.8v4h4" stroke="currentColor" stroke-width="1.9" fill="none"/><text x="12" y="15.5" font-size="7.5" text-anchor="middle" fill="currentColor" font-family="Inter,sans-serif" font-weight="700">10</text>',
  fwd10: '<path d="M19 12a7 7 0 1 1-2.1-5" stroke="currentColor" stroke-width="1.9" fill="none"/><path d="M19.5 3.8v4h-4" stroke="currentColor" stroke-width="1.9" fill="none"/><text x="12" y="15.5" font-size="7.5" text-anchor="middle" fill="currentColor" font-family="Inter,sans-serif" font-weight="700">10</text>',
  next: '<path d="M5 5l10 7-10 7z" fill="currentColor"/><path d="M18 5v14" stroke="currentColor" stroke-width="2.4"/>',
  episodes: '<rect x="3" y="7" width="14" height="10" rx="1" stroke="currentColor" stroke-width="1.8" fill="none"/><path d="M6 4h15v11" stroke="currentColor" stroke-width="1.8" fill="none"/>',
  subs: '<rect x="2.5" y="5" width="19" height="14" rx="2" stroke="currentColor" stroke-width="1.8" fill="none"/><path d="M6 11h5M13 11h5M6 15h8M16 15h2" stroke="currentColor" stroke-width="1.8"/>',
  speed: '<path d="M4.6 17a8.5 8.5 0 1 1 14.8 0" stroke="currentColor" stroke-width="1.9" fill="none"/><path d="M12 13.5l4.5-5" stroke="currentColor" stroke-width="2.1"/><circle cx="12" cy="13.6" r="1.5" fill="currentColor"/>',
  full: '<path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5" stroke="currentColor" stroke-width="2" fill="none"/>',
  unfull: '<path d="M9 4v5H4M20 9h-5V4M15 20v-5h5M4 15h5v5" stroke="currentColor" stroke-width="2" fill="none"/>',
  pencil: '<path d="M4 20h4l11-11-4-4L4 16z" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linejoin="round"/>',
  caret: '<path d="M6 9l6 6 6-6z" fill="currentColor"/>',
  gear: '<circle cx="12" cy="12" r="3.2" stroke="currentColor" stroke-width="1.8" fill="none"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" stroke="currentColor" stroke-width="1.8"/>',
  logout: '<path d="M14 4h5v16h-5M10 8l-4 4 4 4M6 12h10" stroke="currentColor" stroke-width="1.8" fill="none"/>',
};

/** Profil resmi: renkli kare + gülen yüz (Netflix tarzı, kendi çizimimiz). */
export const AVATAR_COLORS = ["#2b6cd9", "#e50914", "#f5b400", "#1fa04f", "#8e44ad", "#e67e22", "#16a3a3", "#d63384"];
export function avatar(index = 0, size = 32) {
  const c = AVATAR_COLORS[((index % AVATAR_COLORS.length) + AVATAR_COLORS.length) % AVATAR_COLORS.length];
  const faces = [
    '<circle cx="35" cy="42" r="5"/><circle cx="65" cy="42" r="5"/><path d="M30 64q20 18 40 0" stroke="#fff" stroke-width="6" fill="none" stroke-linecap="round"/>',
    '<rect x="29" y="37" width="10" height="10" rx="2"/><rect x="61" y="37" width="10" height="10" rx="2"/><path d="M32 66h36" stroke="#fff" stroke-width="6" stroke-linecap="round"/>',
    '<circle cx="35" cy="42" r="6"/><circle cx="65" cy="42" r="6"/><ellipse cx="50" cy="68" rx="9" ry="7"/>',
    '<path d="M28 44q7-8 14 0M58 44q7-8 14 0" stroke="#fff" stroke-width="5" fill="none" stroke-linecap="round"/><path d="M30 62q20 20 40 0z"/>',
  ];
  const el = document.createElement("span");
  el.className = "avatar";
  el.style.width = el.style.height = size + "px";
  el.innerHTML = `<svg viewBox="0 0 100 100"><rect width="100" height="100" rx="8" fill="${c}"/><g fill="#fff">${faces[index % faces.length]}</g></svg>`;
  return el;
}

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
