package com.gflix.app.player

import android.content.Context
import android.graphics.Color as AColor
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.gflix.app.R

/**
 * Altyazı görünümü (Netflix gibi): boyut, konum, arka plan. Telefonda saklanır, tüm profillerde geçerli.
 * Konum, görüntünün altından yüzde olarak; kontroller açıkken altyazı ilerleme çubuğunun üstüne çıkar.
 */
object SubtitleStyle {
    enum class Size(val label: String, val sp: Float) { Small("Küçük", 18f), Medium("Orta", 22f), Large("Büyük", 27f), Huge("Çok Büyük", 33f) }
    enum class Background(val label: String) { Shadow("Gölge"), Outline("Kenarlık"), Box("Koyu Kutu") }

    data class Prefs(val size: Size = Size.Medium, val background: Background = Background.Shadow)

    /**
     * Altyazı katmanının ne kadar yukarı kaydırılacağı (görüntü yüksekliğinin oranı). Altyazı dosyaları (SRT/VTT)
     * satır konumu taşıdığında Media3 "alttan boşluk" ayarını yok sayar; bu yüzden katmanın tamamı kaydırılır.
     */
    const val LIFT_NORMAL = 0.09f
    /** Kontroller açıkken: ilerleme çubuğu ve düğmelerin üstü */
    const val LIFT_CONTROLS = 0.24f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gflix.subtitles", Context.MODE_PRIVATE)

    fun load(ctx: Context): Prefs {
        val p = prefs(ctx)
        return Prefs(
            Size.entries.firstOrNull { it.name == p.getString("size", null) } ?: Size.Medium,
            Background.entries.firstOrNull { it.name == p.getString("bg", null) } ?: Background.Shadow,
        )
    }

    fun save(ctx: Context, v: Prefs) {
        prefs(ctx).edit().putString("size", v.size.name).putString("bg", v.background.name).apply()
    }

    @OptIn(UnstableApi::class)
    fun apply(view: SubtitleView, v: Prefs, lift: Float) {
        val font = ResourcesCompat.getFont(view.context, R.font.inter_semibold)
        val style = when (v.background) {
            Background.Shadow -> CaptionStyleCompat(AColor.WHITE, AColor.TRANSPARENT, AColor.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, AColor.BLACK, font)
            Background.Outline -> CaptionStyleCompat(AColor.WHITE, AColor.TRANSPARENT, AColor.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, AColor.BLACK, font)
            Background.Box -> CaptionStyleCompat(AColor.WHITE, AColor.argb(190, 0, 0, 0), AColor.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, AColor.BLACK, font)
        }
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(style)
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, v.size.sp)
        view.setBottomPaddingFraction(0.04f)
        // Katmanı kaydır (dosyadaki satır konumundan bağımsız). Yükseklik ilk çizimde belli olur: değişince yeniden hesapla.
        view.setTag(R.id.subtitle_lift, lift)
        view.translationY = -view.height * lift
        if (view.getTag(R.id.subtitle_lift_listener) == null) {
            view.setTag(R.id.subtitle_lift_listener, true)
            view.addOnLayoutChangeListener { lv, _, top, _, bottom, _, _, _, _ ->
                lv.translationY = -(bottom - top) * ((lv.getTag(R.id.subtitle_lift) as? Float) ?: LIFT_NORMAL)
            }
        }
    }
}
