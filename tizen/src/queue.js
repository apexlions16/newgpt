// "Sıradasın": bilgisayardaki sunucu GitHub istek hakkının dolduğunu bildirirse yeni izleme bekletilir
// (kullanıcıya sınır gösterilmez); sıra gelince kendiliğinden başlar / kaldığı yerden devam eder.
import { Net } from "./core.js";
import { h } from "./ui.js";

/** Sunucu: şimdi başlayabilir mi? (sunucuya ulaşılamazsa engelleme: oynatıcı kendi hatasını gösterir) */
export async function canStart() {
  const p = await Net.ping(Net.base, 2500);
  return !p || p.canStart !== false ? { ok: true } : { ok: false, waitMs: p.waitMs || 0 };
}

/** Kapsayıcının üstüne "Sıradasın" katmanı koyar; sıra gelince [onReady]. Döner: kaldır() */
export function queueOverlay(parent, title, resume, onReady) {
  const dots = h("div.q-dots", h("i"), h("i"), h("i"));
  const est = h("div.q-est");
  const el = h("div.queue", h("div.q-logo", "G-FLIX"), dots, h("div.q-title", "Sıradasın"),
    h("div.q-text", resume ? `Şu an çok yoğun. “${title}” kaldığın yerden kendiliğinden devam edecek.` : `Şu an çok yoğun. “${title}” sıran gelince kendiliğinden başlayacak.`),
    est, h("div.q-hint", "Geri: vazgeç"));
  parent.appendChild(el);
  let alive = true;
  (async function poll() {
    while (alive) {
      const r = await canStart();
      if (!alive) return;
      if (r.ok) { remove(); onReady(); return; }
      const min = Math.ceil(r.waitMs / 60000);
      est.textContent = r.waitMs > 0 ? (min <= 1 ? "Tahmini: 1 dakikadan az" : `Tahmini: ~${min} dakika`) : "";
      await new Promise((ok) => setTimeout(ok, 5000));
    }
  })();
  function remove() { alive = false; el.remove(); }
  return remove;
}
