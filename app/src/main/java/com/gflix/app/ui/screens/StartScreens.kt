package com.gflix.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.Routes
import com.gflix.app.appContainer
import com.gflix.app.data.GitHub
import com.gflix.app.ui.components.GLogo
import com.gflix.app.ui.theme.GColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(onDone: (String) -> Unit) {
    val context = LocalContext.current
    val animDone = remember { kotlinx.coroutines.CompletableDeferred<Unit>() }
    LaunchedEffect(Unit) {
        val c = context.appContainer
        val state = c.userStore.current()
        c.tokens.token = state.token ?: c.builtInToken
        c.catalogRepository.loadCached()
        val archive = com.gflix.app.archive.ArchiveSource.configured
        if (c.hasToken || archive) c.appScope.launch { c.catalogRepository.refresh() }
        animDone.await()                                         // giriş animasyonu bitsin (dokununca atlanır)
        onDone(
            when {
                !c.hasToken && !archive -> Routes.SETUP
                state.profiles.isEmpty() -> Routes.profileEdit("new", first = true)
                else -> Routes.profiles()
            }
        )
    }
    com.gflix.app.ui.components.GFlixIntro(
        Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
        ) { animDone.complete(Unit) },
        onFinished = { animDone.complete(Unit) },
    )
}

@Composable
fun gTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF333333),
    unfocusedContainerColor = Color(0xFF333333),
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Color.Transparent,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedLabelColor = GColors.TextSecondary,
    unfocusedLabelColor = GColors.TextSecondary,
)

/** Uygulamaya gömülü erişim anahtarı yoksa bir kez sorulur. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        GLogo(size = 44.sp)
        Spacer(Modifier.height(32.dp))
        Text("Hoş geldin!", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        var iaPrefix by remember { mutableStateOf("") }
        Text(
            "İçerikler archive.org'da duruyor. Arşivin adını (önekini) gir — yöneticinin Stüdyo Ayarları'nda gördüğü ad, ör. gflix-ahmet. " +
                "Anahtar gerekmez.",
            color = GColors.TextSecondary, fontSize = 15.sp,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = iaPrefix, onValueChange = { iaPrefix = it; error = null }, label = { Text("Arşiv adı") }, singleLine = true,
            colors = gTextFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val c = context.appContainer
                    com.gflix.app.archive.ArchiveSource.save(context, iaPrefix)
                    val ok = c.catalogRepository.refresh()
                    busy = false
                    val status = c.catalogRepository.status.value
                    if (ok || (status is com.gflix.app.data.CatalogStatus.Error && status.message.startsWith("Katalog bulunamadı"))) onDone()
                    else error = (status as? com.gflix.app.data.CatalogStatus.Error)?.message ?: "Bağlantı kurulamadı."
                }
            },
            enabled = com.gflix.app.archive.ArchiveSource.normalize(iaPrefix).isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
        ) { Text(if (busy) "Kontrol ediliyor…" else "Arşive bağlan", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(32.dp))
        Text("Eski düzen (GitHub)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "İçeriklerin private GitHub deposunda (${GitHub.owner}/${GitHub.repo}) duruyor. " +
                "Uygulamanın bu depoyu okuyabilmesi için bir GitHub erişim anahtarı (fine-grained token, " +
                "sadece 'Contents: Read' yetkisiyle) gir.",
            color = GColors.TextSecondary, fontSize = 15.sp,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text("GitHub erişim anahtarı") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = gTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFE87C03), fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val c = context.appContainer
                    c.userStore.setToken(token)
                    c.tokens.token = token.trim()
                    val ok = c.catalogRepository.refresh()
                    busy = false
                    val status = c.catalogRepository.status.value
                    if (ok || (status is com.gflix.app.data.CatalogStatus.Error && (status.message.startsWith("Katalog bulunamadı") || status.message.startsWith("Katalog evdeki bilgisayarda")))) {
                        onDone()
                    } else {
                        error = (status as? com.gflix.app.data.CatalogStatus.Error)?.message ?: "Bağlantı kurulamadı."
                    }
                }
            },
            enabled = token.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GColors.Red, contentColor = Color.White),
        ) {
            Text(if (busy) "Kontrol ediliyor…" else "Devam", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
