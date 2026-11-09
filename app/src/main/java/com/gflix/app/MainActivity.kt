package com.gflix.app

import kotlinx.coroutines.launch
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gflix.app.admin.AdminHomeScreen
import com.gflix.app.admin.AdminLinksScreen
import com.gflix.app.admin.AdminMergeScreen
import com.gflix.app.admin.AdminManageScreen
import com.gflix.app.admin.AdminSettingsScreen
import com.gflix.app.admin.AdminUploadScreen
import com.gflix.app.player.PlayerActivity
import com.gflix.app.ui.screens.DetailScreen
import com.gflix.app.ui.screens.MainScreen
import com.gflix.app.ui.screens.MyListScreen
import com.gflix.app.ui.screens.ProfileEditScreen
import com.gflix.app.ui.screens.ProfilesScreen
import com.gflix.app.ui.screens.SearchScreen
import com.gflix.app.ui.screens.SettingsScreen
import com.gflix.app.ui.screens.SetupScreen
import com.gflix.app.ui.screens.SplashScreen
import com.gflix.app.ui.theme.GColors
import com.gflix.app.ui.theme.GFlixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        // İstek listesi çalışır durumda bırakıldıysa (ör. TV kutusu yeniden açıldı) Stüdyo'ya girmeden de sürsün
        if (com.gflix.app.admin.WishList.wasStarted(this)) com.gflix.app.admin.CaptureEngine.init(this, fromCreate = true)
        handleOpen(intent)
        setContent {
            GFlixTheme {
                // Sayfadan Yakala sırası sürerken ekrandan çıkıldıysa tarayıcı en arkada çalışmaya devam eder
                com.gflix.app.admin.CaptureBackgroundHost()
                GFlixNavHost()
                CrashReport()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** Hatırlatma bildiriminden gelindiyse yapımın sayfası açılır. */
    private fun handleOpen(intent: android.content.Intent?) {
        intent?.getStringExtra(com.gflix.app.data.Reminders.EXTRA_OPEN_TITLE)?.let { com.gflix.app.data.PendingOpen.id.value = it }
        intent?.getStringExtra(com.gflix.app.admin.CaptureService.EXTRA_OPEN_ROUTE)?.let { com.gflix.app.data.PendingOpen.route.value = it }
        // QR'dan bilgisayar bağlantısı: kaydet, kataloğu bilgisayardan yenile
        intent?.data?.let { uri ->
            if (com.gflix.app.data.PcServer.savePairLink(this, uri)) {
                android.widget.Toast.makeText(this, "🖥️ Bilgisayara bağlandı", android.widget.Toast.LENGTH_SHORT).show()
                val c = appContainer
                c.appScope.launch { c.catalogRepository.refresh() }
            }
        }
    }
}

object Routes {
    const val SPLASH = "splash"
    const val SETUP = "setup"
    const val PROFILES = "profiles?manage={manage}"
    const val PROFILE_EDIT = "profile/{id}?first={first}"
    const val MAIN = "main"
    const val TITLE = "title/{id}"
    const val SEARCH = "search"
    const val MY_LIST = "mylist"
    const val SETTINGS = "settings"
    const val ADMIN = "admin"
    const val ADMIN_SETTINGS = "admin/settings"
    const val ADMIN_UPLOAD = "admin/upload?trailer={trailer}"
    const val ADMIN_TITLE = "admin/title/{id}"
    const val ADMIN_LINKS = "admin/links?item={item}"
    fun adminLinks(item: String? = null) = "admin/links?item=${item.orEmpty()}"
    const val ADMIN_MERGE = "admin/merge?item={item}"
    fun adminMerge(item: String? = null) = "admin/merge?item=${item.orEmpty()}"
    const val ADMIN_SOON = "admin/soon"
    const val ADMIN_CAPTURE = "admin/capture?q={q}&item={item}"
    fun adminCapture(q: String? = null, item: String? = null) =
        "admin/capture?q=${android.net.Uri.encode(q.orEmpty())}&item=${item.orEmpty()}"
    const val ADMIN_LOG = "admin/log"
    const val DOWNLOADS = "downloads"

    fun adminUpload(trailerFor: String? = null) = "admin/upload?trailer=${trailerFor.orEmpty()}"
    fun adminTitle(id: String) = "admin/title/$id"

    fun profiles(manage: Boolean = false) = "profiles?manage=$manage"
    fun profileEdit(id: String, first: Boolean = false) = "profile/$id?first=$first"
    fun title(id: String) = "title/$id"
}

private fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(0) { inclusive = true }
    launchSingleTop = true
}

@Composable
fun GFlixNavHost() {
    val nav = rememberNavController()
    val openTitle: (String) -> Unit = { nav.navigate(Routes.title(it)) { launchSingleTop = true } }
    // Bildirimden (Sayfadan Yakala dokunuş bekliyor): hangi ekranda olursan ol o ekran açılır
    val pendingRoute by com.gflix.app.data.PendingOpen.route.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    androidx.compose.runtime.LaunchedEffect(pendingRoute, backStack?.destination?.route) {
        val r = pendingRoute ?: return@LaunchedEffect
        val cur = backStack?.destination?.route ?: return@LaunchedEffect
        if (cur == Routes.SPLASH || cur == Routes.SETUP || cur.startsWith("profiles")) return@LaunchedEffect   // önce profil seçilsin
        com.gflix.app.data.PendingOpen.route.value = null
        if (r == "admin/capture" && cur != Routes.ADMIN_CAPTURE) nav.navigate(Routes.adminCapture()) { launchSingleTop = true }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        modifier = Modifier.fillMaxSize().background(GColors.Black),
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(220)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(220)) },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen { destination -> nav.resetTo(destination) }
        }
        composable(Routes.SETUP) {
            SetupScreen(onDone = { nav.resetTo(Routes.SPLASH) })
        }
        composable(
            Routes.PROFILES,
            arguments = listOf(navArgument("manage") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            ProfilesScreen(
                manageInitially = entry.arguments?.getBoolean("manage") ?: false,
                onSelected = { nav.resetTo(Routes.MAIN) },
                onEdit = { nav.navigate(Routes.profileEdit(it)) },
                onAdd = { nav.navigate(Routes.profileEdit("new")) },
            )
        }
        composable(
            Routes.PROFILE_EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("first") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val first = entry.arguments?.getBoolean("first") ?: false
            ProfileEditScreen(
                profileId = entry.arguments?.getString("id") ?: "new",
                isFirst = first,
                onDone = { if (first) nav.resetTo(Routes.profiles()) else nav.popBackStack() },
            )
        }
        composable(Routes.MAIN) {
            val pending by com.gflix.app.data.PendingOpen.id.collectAsState()
            androidx.compose.runtime.LaunchedEffect(pending) {
                pending?.let { com.gflix.app.data.PendingOpen.id.value = null; openTitle(it) }
            }
            MainScreen(
                onOpenTitle = openTitle,
                onSearch = { nav.navigate(Routes.SEARCH) },
                onSwitchProfile = { nav.resetTo(Routes.profiles()) },
                onManageProfiles = { nav.navigate(Routes.profiles(manage = true)) },
                onMyList = { nav.navigate(Routes.MY_LIST) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onEditProfile = { nav.navigate(Routes.profileEdit(it)) },
                onAdmin = { nav.navigate(Routes.ADMIN) },
                onDownloads = { nav.navigate(Routes.DOWNLOADS) },
            )
        }
        composable(Routes.TITLE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            DetailScreen(
                titleId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenTitle = openTitle,
            )
        }
        composable(Routes.DOWNLOADS) {
            com.gflix.app.ui.screens.DownloadsScreen(onBack = { nav.popBackStack() }, onOpenTitle = openTitle)
        }
        composable(Routes.SEARCH) {
            SearchScreen(onBack = { nav.popBackStack() }, onOpenTitle = openTitle)
        }
        composable(Routes.MY_LIST) {
            MyListScreen(onBack = { nav.popBackStack() }, onOpenTitle = openTitle)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ADMIN) {
            AdminHomeScreen(
                onBack = { nav.popBackStack() },
                onUpload = { nav.navigate(Routes.adminUpload()) },
                onEditTitle = { nav.navigate(Routes.adminTitle(it)) },
                onSettings = { nav.navigate(Routes.ADMIN_SETTINGS) },
                onLinks = { nav.navigate(Routes.adminLinks()) },
                onMerge = { nav.navigate(Routes.adminMerge()) },
                onSoon = { nav.navigate(Routes.ADMIN_SOON) },
                onCapture = { nav.navigate(Routes.adminCapture()) },
                onLog = { nav.navigate(Routes.ADMIN_LOG) },
            )
        }
        composable(Routes.ADMIN_LOG) {
            com.gflix.app.admin.AdminLogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ADMIN_CAPTURE, arguments = listOf(
            navArgument("q") { type = NavType.StringType; defaultValue = "" },
            navArgument("item") { type = NavType.StringType; defaultValue = "" },
        )) { entry ->
            com.gflix.app.admin.AdminCaptureScreen(
                onBack = { nav.popBackStack() }, onJobs = { nav.navigate(Routes.adminLinks()) },
                presetQuery = entry.arguments?.getString("q")?.ifBlank { null },
                presetItem = entry.arguments?.getString("item")?.ifBlank { null },
            )
        }
        composable(Routes.ADMIN_SOON) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            com.gflix.app.admin.AdminSoonScreen(
                onBack = { nav.popBackStack() },
                onUploadTrailer = { nav.navigate(Routes.adminUpload(it)) },
                onEditTitle = { nav.navigate(Routes.adminTitle(it)) },
                onAddMedia = { id, way ->
                    when (way) {
                        "file" -> nav.navigate(Routes.adminMerge(id))
                        "capture" -> {
                            val t = ctx.appContainer.catalogRepository.catalog.value?.titles?.firstOrNull { it.id == id }
                                ?.let { com.gflix.app.data.Presentation.localize(it, null).title }
                            nav.navigate(Routes.adminCapture(t.orEmpty(), id))
                        }
                        else -> nav.navigate(Routes.adminLinks(id))
                    }
                },
            )
        }
        composable(Routes.ADMIN_MERGE, arguments = listOf(navArgument("item") { type = NavType.StringType; defaultValue = "" })) { entry ->
            AdminMergeScreen(
                presetItem = entry.arguments?.getString("item")?.ifBlank { null },
                onBack = { nav.popBackStack() },
                onSent = { nav.navigate(Routes.adminLinks()) { popUpTo(Routes.ADMIN) } },
            )
        }
        composable(Routes.ADMIN_LINKS, arguments = listOf(navArgument("item") { type = NavType.StringType; defaultValue = "" })) { entry ->
            AdminLinksScreen(onBack = { nav.popBackStack() }, presetItem = entry.arguments?.getString("item")?.ifBlank { null })
        }
        composable(Routes.ADMIN_SETTINGS) {
            AdminSettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ADMIN_UPLOAD, arguments = listOf(navArgument("trailer") { type = NavType.StringType; defaultValue = "" })) { entry ->
            AdminUploadScreen(
                onBack = { nav.popBackStack() },
                onStarted = { nav.popBackStack(Routes.ADMIN, inclusive = false) },
                presetTrailerFor = entry.arguments?.getString("trailer")?.ifBlank { null },
            )
        }
        composable(Routes.ADMIN_TITLE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            AdminManageScreen(
                titleId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onUploadTrailer = { nav.navigate(Routes.adminUpload(it)) },
            )
        }
    }
}

/** Oynatıcıyı başlatır. */
fun android.content.Context.startPlayback(titleId: String, season: Int?, episode: Int?, positionMs: Long) =
    PlayerActivity.start(this, titleId, season, episode, positionMs)

/** Önceki açılışta çöktüyse hatayı gösterir; "Kopyala" ile panoya alınıp gönderilebilir. */
@Composable
private fun CrashReport() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = (context.applicationContext as GFlixApp).crashFile()
    val text = androidx.compose.runtime.remember { if (file.exists()) file.readText() else null } ?: return
    val shown = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    if (!shown.value) return
    val dismiss = { file.delete(); shown.value = false }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = dismiss,
        containerColor = GColors.Surface,
        title = { androidx.compose.material3.Text("Uygulama son açılışta kapandı", color = androidx.compose.ui.graphics.Color.White) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text("Hata kaydını kopyalayıp gönderirsen sorunu bulup düzeltebiliriz.", color = GColors.TextSecondary)
                androidx.compose.material3.Text(
                    text.lines().take(8).joinToString("\n"), color = GColors.TextMuted, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    modifier = androidx.compose.ui.Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("G-Flix hata", text))
                android.widget.Toast.makeText(context, "Hata kaydı kopyalandı", android.widget.Toast.LENGTH_SHORT).show()
                dismiss()
            }) { androidx.compose.material3.Text("Kopyala", color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { androidx.compose.material3.Text("Kapat", color = GColors.TextSecondary) } },
    )
}
