package com.gflix.app.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gflix.app.archive.PhoneArchiveWorker
import com.gflix.app.archive.PhoneProgress
import com.gflix.app.ui.theme.GColors

@Composable
internal fun PhoneTaskProgress(task: UploadTask) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(PhoneProgress.label(task.phase) + if (task.percent >= 0) " · %${task.percent}" else "",
            color = GColors.TextSecondary, fontSize = 12.sp)
        if (task.percent >= 0) LinearProgressIndicator(
            progress = { task.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(task.message ?: "Hazırlanıyor…", color = GColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
internal fun PhoneQueueProgress() {
    val ctx = LocalContext.current
    val store = remember { AdminStore(ctx) }
    val state by store.state.collectAsState(initial = null)
    val active = state?.tasks.orEmpty().filter { it.tag == PhoneArchiveWorker.TAG && it.status != TaskStatus.DONE }
        .minByOrNull { it.createdAt }
    active?.let { PhoneTaskProgress(it) }
}
