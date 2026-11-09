package com.gflix.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.ui.components.ProfileAvatar
import com.gflix.app.ui.theme.GColors

@Composable
fun MainScreen(
    onOpenTitle: (String) -> Unit,
    onSearch: () -> Unit,
    onSwitchProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    onMyList: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: (String) -> Unit,
    onAdmin: () -> Unit,
    onDownloads: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var notices by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val lib = rememberLibraryState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val noticeList = remember(lib.titles) { Notices.list(lib.titles) }
    var unseen by remember(noticeList) { mutableIntStateOf(Notices.unseen(context, noticeList)) }
    val openNotices = { notices = true }

    if (notices) {
        androidx.activity.compose.BackHandler { notices = false; unseen = 0 }
        NotificationsScreen(noticeList, onBack = { notices = false; unseen = 0 }, onOpenTitle = onOpenTitle)
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> HomeScreen(lib, onOpenTitle, { tab = 2 }, onSwitchProfile, onDownloads, openNotices, unseen)
                    1 -> NewHotScreen(lib, onOpenTitle, { tab = 2 })
                    2 -> SearchScreen(onBack = { tab = 0 }, onOpenTitle = onOpenTitle, asTab = true, onDownloads = onDownloads,
                        onNotifications = openNotices, unseen = unseen)
                    else -> MyGFlixScreen(lib, onOpenTitle, { tab = 2 }, onSwitchProfile, onManageProfiles, onMyList, onSettings, onEditProfile, onAdmin,
                        onDownloads, openNotices, unseen)
                }
            }
        }
        // Netflix'teki gibi yuvarlak, havada duran alt menü
        Box(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(50)).background(Color(0xFF232323))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomTab("Ana Sayfa", if (tab == 0) Icons.Filled.Home else Icons.Outlined.Home, tab == 0, Modifier.weight(1f)) { tab = 0 }
                BottomTab("Yeni ve Popüler", if (tab == 1) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary, tab == 1, Modifier.weight(1.2f)) { tab = 1 }
                BottomTab("Ara", Icons.Filled.Search, tab == 2, Modifier.weight(1f)) { tab = 2 }
                Column(
                    Modifier.weight(1.3f).fillMaxSize().clip(RoundedCornerShape(50)).background(if (tab == 3) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, null) { tab = 3 },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(24.dp)) { ProfileAvatar(lib.profile?.avatar ?: 0, Modifier.fillMaxSize(), corner = 5.dp) }
                    Spacer(Modifier.height(3.dp))
                    Text("Benim G-Flix'im", fontSize = 10.sp, color = if (tab == 3) Color.White else GColors.TextMuted, maxLines = 1,
                        fontWeight = if (tab == 3) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun BottomTab(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = if (selected) Color.White else GColors.TextMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = if (selected) Color.White else GColors.TextMuted, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
