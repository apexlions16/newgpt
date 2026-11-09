// Kullanıcı verisi (bu bilgisayarda): profiller, Listem, beğeniler, izleme ilerlemesi, ses/altyazı tercihleri.
const KEY = "gflix.v1";

function load() {
  try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (_) { return {}; }
}

const state = Object.assign({ profiles: [], active: null, data: {} }, load());
const listeners = new Set();

function save() {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (_) { /* depolama kapalı */ }
  listeners.forEach((f) => f());
}

export const Store = {
  get state() { return state; },
  onChange(f) { listeners.add(f); return () => listeners.delete(f); },

  get profile() { return state.profiles.find((p) => p.id === state.active) || null; },
  get data() { return Store.dataFor(state.active); },
  dataFor(id) {
    if (!id) return { myList: [], liked: [], progress: {} };
    state.data[id] = Object.assign({ myList: [], liked: [], progress: {}, audioLang: null, subtitleLang: null }, state.data[id] || {});
    return state.data[id];
  },

  setActive(id) { state.active = id; save(); },
  addProfile(p) {
    const prof = Object.assign({ id: "p" + Date.now().toString(36), name: "Profil", avatar: state.profiles.length, kids: false,
      titleLang: "tr", artMode: "rotate", autoplayTrailers: true }, p);
    state.profiles.push(prof);
    save();
    return prof;
  },
  updateProfile(id, patch) { Object.assign(state.profiles.find((p) => p.id === id) || {}, patch); save(); },
  deleteProfile(id) {
    state.profiles = state.profiles.filter((p) => p.id !== id);
    delete state.data[id];
    if (state.active === id) state.active = null;
    save();
  },

  toggleList(titleId) {
    const d = Store.data;
    d.myList = d.myList.includes(titleId) ? d.myList.filter((x) => x !== titleId) : [titleId, ...d.myList];
    save();
  },
  toggleLike(titleId) {
    const d = Store.data;
    d.liked = d.liked.includes(titleId) ? d.liked.filter((x) => x !== titleId) : [titleId, ...d.liked];
    save();
  },
  saveProgress(p) {
    const d = Store.data;
    const key = p.season == null ? p.titleId : `${p.titleId}/${p.season}/${p.episode}`;
    d.progress[key] = Object.assign({}, p, { updatedAt: Date.now() });
    save();
  },
  setLanguages(audio, sub) {
    const d = Store.data;
    if (audio !== undefined) d.audioLang = audio;
    if (sub !== undefined) d.subtitleLang = sub;
    save();
  },
};

export const isFinished = (p) => p && p.durationMs > 0 && (p.positionMs >= p.durationMs * 0.95 || p.durationMs - p.positionMs < 30000);
export const fraction = (p) => (p && p.durationMs > 0 ? Math.min(1, Math.max(0, p.positionMs / p.durationMs)) : 0);
