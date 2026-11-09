package com.gflix.app.admin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gflix.app.data.AppJson
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date

/** Dosya türü (uzantıdan) */
enum class FileKind(val label: String) {
    Video("Video"), Subtitle("Altyazı"), Audio("Ses"), Other("Diğer");

    companion object {
        private val VIDEO = setOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "webm", "wmv", "mpg", "mpeg", "flv")
        private val SUB = setOf("srt", "vtt", "ass", "ssa", "sub", "sup", "ttml", "dfxp")
        private val AUDIO = setOf("m4a", "aac", "ac3", "eac3", "ec3", "dts", "mka", "mp3", "opus", "flac", "wav", "ogg")
        fun of(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            in VIDEO -> Video; in SUB -> Subtitle; in AUDIO -> Audio; else -> Other
        }
    }
}

@Serializable
private data class Crumb(val docId: String, val name: String)

@Serializable
private data class BrowserMemory(val roots: List<String> = emptyList(), val lastRoot: String? = null, val lastPath: List<Crumb> = emptyList())

private data class Entry(val docId: String, val name: String, val dir: Boolean, val size: Long, val modified: Long, val kind: FileKind)

/**
 * Uygulama içi dosya seçici: telefonun bir klasörüne bir kez izin verilir; adlar kesilmeden gösterilir,
 * en son kalınan klasör hatırlanır, çoklu seçim, tür filtresi.
 */
class FileBrowserState {
    internal var request by mutableStateOf<Request?>(null)
    internal class Request(val title: String, val kinds: Set<FileKind>, val multi: Boolean, val onPicked: (List<Uri>) -> Unit)

    fun open(title: String, kinds: Set<FileKind>, multi: Boolean = true, onPicked: (List<Uri>) -> Unit) {
        request = Request(title, kinds, multi, onPicked)
    }
}

@Composable
fun rememberFileBrowser(): FileBrowserState {
    val state = remember { FileBrowserState() }
    // Sistem çubuklarının yüksekliği ana ekrandan ölçülür: tam ekran pencerede alt çubuk gezinme tuşlarının altında kalmasın
    val density = androidx.compose.ui.platform.LocalDensity.current
    val top = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    state.request?.let { r -> FileBrowserDialog(r, PaddingValues(top = top, bottom = bottom)) { state.request = null } }
    return state
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("gflix.files", Context.MODE_PRIVATE)
private fun loadMemory(ctx: Context): BrowserMemory =
    prefs(ctx).getString("m", null)?.let { runCatching { AppJson.decodeFromString<BrowserMemory>(it) }.getOrNull() } ?: BrowserMemory()
private fun saveMemory(ctx: Context, m: BrowserMemory) = prefs(ctx).edit().putString("m", AppJson.encodeToString(BrowserMemory.serializer(), m)).apply()

private fun rootName(ctx: Context, tree: Uri): String = runCatching {
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull() ?: "Klasör"

private suspend fun listChildren(ctx: Context, tree: Uri, docId: String): List<Entry> = withContext(Dispatchers.IO) {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    val out = mutableListOf<Entry>()
    ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            if (name.startsWith(".")) continue
            val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
            out += Entry(c.getString(0), name, dir, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4), if (dir) FileKind.Other else FileKind.of(name))
        }
    }
    out
}

private fun human(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(com.gflix.app.data.TR, "%.2f GB", n / 1e9)
    n >= 1_000_000 -> String.format(com.gflix.app.data.TR, "%.0f MB", n / 1e6)
    n >= 1_000 -> String.format(com.gflix.app.data.TR, "%.0f KB", n / 1e3)
    else -> "$n B"
}

@Composable
private fun FileBrowserDialog(req: FileBrowserState.Request, bars: PaddingValues, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var memory by remember { mutableStateOf(loadMemory(ctx)) }
    var root by remember { mutableStateOf(memory.lastRoot?.takeIf { it in memory.roots } ?: memory.roots.firstOrNull()) }
    val path = remember { mutableStateListOf<Crumb>().apply { if (root == memory.lastRoot) addAll(memory.lastPath) } }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<FileKind?>(null) }
    var byName by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, String>() } // docId -> ad
    var rootMenu by remember { mutableStateOf(false) }

    val addRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            memory = memory.copy(roots = (memory.roots + uri.toString()).distinct())
            saveMemory(ctx, memory)
            root = uri.toString(); path.clear()
        }
    }

    val tree = root?.let { Uri.parse(it) }
    val currentDoc = tree?.let { path.lastOrNull()?.docId ?: DocumentsContract.getTreeDocumentId(it) }
    LaunchedEffect(root, path.toList()) {
        entries = null; error = null
        if (tree == null || currentDoc == null) return@LaunchedEffect
        runCatching { listChildren(ctx, tree, currentDoc) }
            .onSuccess { entries = it }
            .onFailure {
                // izin kalkmış ya da klasör silinmiş: başa dön
                if (path.isNotEmpty()) { path.clear() } else error = "Klasör açılamadı. Klasöre yeniden izin ver."
            }
        memory = memory.copy(lastRoot = root, lastPath = path.toList()); saveMemory(ctx, memory)
    }
    BackHandler { if (path.isNotEmpty()) path.removeAt(path.lastIndex) else onClose() }

    fun finish(ids: Collection<String>) {
        val t = tree ?: return
        val uris = ids.map { DocumentsContract.buildDocumentUriUsingTree(t, it) }
        onClose(); req.onPicked(uris)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(bars)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (path.isNotEmpty()) path.removeAt(path.lastIndex) else onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White)
                }
                Text(req.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { byName = !byName }) { Text(if (byName) "A→Z" else "En yeni", color = GColors.TextSecondary, fontSize = 13.sp) }
            }
            if (tree == null) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.Folder, null, tint = GColors.TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Dosyaların durduğu klasörü seç", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Hint("Örneğin \"Download\" (İndirilenler). Bir kere izin verirsin; sonra uygulama içinde alt klasörlerde gezip dosya adlarını tam görürsün.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { addRoot.launch(null) }, shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White)) { Text("Klasör seç", fontWeight = FontWeight.Bold) }
                }
                return@Column
            }
            // klasör yolu
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                item {
                    Text("📁 " + rootName(ctx, tree) + " ▾", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF262626)).clickable { rootMenu = !rootMenu }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                items(path.size) { i ->
                    Text("› " + path[i].name, color = if (i == path.lastIndex) Color.White else GColors.TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.clickable { while (path.size > i + 1) path.removeAt(path.lastIndex) }.padding(horizontal = 4.dp, vertical = 6.dp))
                }
            }
            if (rootMenu) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1C1C1C))) {
                    memory.roots.forEach { r ->
                        Text(rootName(ctx, Uri.parse(r)), color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { root = r; path.clear(); rootMenu = false }.padding(12.dp))
                    }
                    Text("+ Başka klasöre izin ver", color = GColors.TextSecondary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clickable { rootMenu = false; addRoot.launch(null) }.padding(12.dp))
                }
            }
            // tür filtresi
            val kinds = listOf<FileKind?>(null) + req.kinds.toList()
            if (kinds.size > 2) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(kinds) { k ->
                    val sel = filter == k
                    Text(k?.label ?: "Hepsi", color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color.White else Color(0xFF333333)).clickable { filter = k }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = entries
                when {
                    error != null -> Text(error!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(16.dp))
                    list == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                    else -> {
                        val dirs = list.filter { it.dir }.sortedBy { it.name.lowercase() }
                        val files = list.filter { !it.dir && it.kind in req.kinds && (filter == null || it.kind == filter) }
                            .let { f -> if (byName) f.sortedBy { it.name.lowercase() } else f.sortedByDescending { it.modified } }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(dirs, key = { "d" + it.docId }) { d ->
                                Row(Modifier.fillMaxWidth().clickable { path.add(Crumb(d.docId, d.name)) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Folder, null, tint = Color(0xFFF5B400), modifier = Modifier.size(26.dp))
                                    Spacer(Modifier.width(14.dp))
                                    Text(d.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                }
                            }
                            items(files, key = { "f" + it.docId }) { f ->
                                val on = f.docId in selected
                                Row(
                                    Modifier.fillMaxWidth().background(if (on) Color(0xFF1F2A3A) else Color.Transparent)
                                        .clickable { if (!req.multi) finish(listOf(f.docId)) else if (on) selected.remove(f.docId) else selected[f.docId] = f.name }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        when (f.kind) { FileKind.Video -> Icons.Outlined.Movie; FileKind.Subtitle -> Icons.Outlined.Subtitles; FileKind.Audio -> Icons.Outlined.Audiotrack; else -> Icons.Outlined.InsertDriveFile },
                                        null, tint = GColors.TextSecondary, modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        // tam ad: kesilmeden, gerekirse birkaç satır
                                        Text(f.name, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp)
                                        Text(human(f.size) + (if (f.modified > 0) " · " + SimpleDateFormat("d MMM yyyy HH:mm", com.gflix.app.data.TR).format(Date(f.modified)) else ""),
                                            color = GColors.TextSecondary, fontSize = 12.sp)
                                    }
                                    if (req.multi) Icon(if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                                        tint = if (on) Color(0xFF2F80ED) else GColors.TextSecondary, modifier = Modifier.padding(start = 8.dp).size(24.dp))
                                }
                            }
                            if (dirs.isEmpty() && files.isEmpty()) item { Hint("Bu klasörde uygun dosya yok.", Modifier.padding(16.dp)) }
                        }
                    }
                }
            }
            if (req.multi) Row(Modifier.fillMaxWidth().background(Color(0xFF121212)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selected.isEmpty()) "Dosyalara dokunarak seç" else "${selected.size} dosya seçildi", color = GColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) TextButton(onClick = { selected.clear() }) { Text("Temizle", color = GColors.TextSecondary) }
                Button(onClick = { finish(selected.keys) }, enabled = selected.isNotEmpty(), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White)) { Text("Ekle", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
