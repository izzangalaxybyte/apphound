package com.thegalaxybyte.apphound.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.thegalaxybyte.apphound.AppRow
import com.thegalaxybyte.apphound.Busy
import com.thegalaxybyte.apphound.StoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(vm: StoreViewModel, onOpenApp: (String) -> Unit, onAllowInstalls: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refreshInstalled()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("apphound") },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.needsInstallPermission) {
                    item {
                        Notice(
                            text = "Allow apphound to install apps, then try again.",
                            action = "Allow" to onAllowInstalls,
                            dismiss = vm::dismissInstallPermission,
                        )
                    }
                }
                state.error?.let { item { Notice(text = "Couldn't load the store: $it", action = "Retry" to vm::refresh) } }
                if (state.updates.size > 1) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${state.updates.size} updates available", Modifier.weight(1f))
                            FilledTonalButton(onClick = vm::updateAll) { Text("Update all") }
                        }
                    }
                }
                if (!state.loading && state.error == null && state.rows.isEmpty()) {
                    item {
                        Text(
                            "No apps published yet. Publish one with scripts/publish.py.",
                            Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.rows, key = { it.app.packageName }) { row ->
                    AppRowItem(row, onInstall = { vm.install(row.app.packageName) }, onOpen = { onOpenApp(row.app.packageName) })
                    HorizontalDivider(Modifier.padding(start = 80.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRowItem(row: AppRow, onInstall: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val app = row.app
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubcomposeAsyncImage(
                model = app.iconUrl,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                error = { Icon(Icons.Default.Android, null, Modifier.size(48.dp)) },
                loading = { Icon(Icons.Default.Android, null, Modifier.size(48.dp)) },
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusLine(row, Formatter.formatShortFileSize(context, app.size)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            ActionButton(row, onInstall, onOpen)
        }
        when (val busy = row.busy) {
            is Busy.Downloading -> LinearProgressIndicator(
                progress = { busy.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            else -> {}
        }
        if (row.updateAvailable && app.changelog.isNotBlank()) {
            Text(
                app.changelog,
                Modifier.padding(start = 64.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        row.error?.let {
            Text(
                it,
                Modifier.padding(start = 64.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun statusLine(row: AppRow, size: String): String {
    val app = row.app
    val installed = row.installed
    return when {
        !row.compatible -> "Needs Android API ${app.minSdk}"
        installed == null -> "${app.versionName} · $size"
        row.updateAvailable -> "${installed.versionName} → ${app.versionName} · $size" +
            if (installed.installedByStore) "" else " · will ask to confirm"
        installed.versionCode > app.versionCode -> "Installed ${installed.versionName} (newer than store)"
        else -> "Up to date · ${installed.versionName}"
    }
}

@Composable
private fun ActionButton(row: AppRow, onInstall: () -> Unit, onOpen: () -> Unit) {
    when {
        row.busy is Busy.Installing || row.busy is Busy.WaitingForConfirmation ->
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        row.busy is Busy.Downloading -> Text("${((row.busy as Busy.Downloading).progress * 100).toInt()}%")
        !row.compatible -> {}
        row.installed == null -> Button(onClick = onInstall) { Text("Install") }
        row.updateAvailable -> Button(onClick = onInstall) { Text("Update") }
        else -> OutlinedButton(onClick = onOpen) { Text("Open") }
    }
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>, dismiss: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismiss?.let { TextButton(onClick = it) { Text("Not now") } }
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}
