# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.gflix.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.gflix.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gflix.app.data.**$$serializer { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Çevrimdışı indirme kayıtları (kotlinx.serialization) — data paketi dışındaki @Serializable sınıflar
-keepclassmembers class com.gflix.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.gflix.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gflix.app.**$$serializer { *; }

# YouTube fragman oynatıcısı (WebView) "bitti" bildirimini çağırır
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
