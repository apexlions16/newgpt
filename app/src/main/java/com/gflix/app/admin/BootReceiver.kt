package com.gflix.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cihaz (ör. TV kutusu) yeniden açılınca ya da uygulama güncellenince: İstek listesi başlatılmış durumdaysa uygulama açılır, liste kaldığı yerden sürer. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // açılışta ya da uygulama güncellenince (güncelleme uygulamayı kapatır): liste çalışır durumdaysa yeniden aç
        if ((i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) || !WishList.wasStarted(c)) return
        runCatching {
            c.startActivity(Intent(c, com.gflix.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
