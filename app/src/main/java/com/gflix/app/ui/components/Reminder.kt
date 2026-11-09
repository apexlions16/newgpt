package com.gflix.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.gflix.app.data.Reminders
import com.gflix.app.data.Title

/** Netflix'teki "Hatırlat" düğmesi: yayına girince bildirim gelir. */
@Composable
fun ReminderAction(title: Title, releaseAt: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember(title.id) { mutableStateOf(Reminders.isOn(context, title.id)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    IconAction(if (on) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone, if (on) "Hatırlatılacak" else "Hatırlat", modifier) {
        on = Reminders.toggle(context, title, releaseAt)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        Toast.makeText(context, if (on) "Yayına girince haber vereceğiz 🔔" else "Hatırlatma kapatıldı", Toast.LENGTH_SHORT).show()
    }
}
