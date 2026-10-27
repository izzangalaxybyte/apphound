package com.thegalaxybyte.apphound.ui

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
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
    val tv = isTv()
    // Every time the store comes to the front (first launch or reopened from the background):
    // update installed versions right away, then fetch the catalog.
    LifecycleResumeEffect(Unit) {
        vm.refreshInstalled()
        vm.refresh()
        onPauseOrDispose { }
    }

    // On a TV nothing has focus at launch, so the first D-pad press goes nowhere useful. Put focus
    // on the first app once the list has loaded (only once, so a refresh doesn't steal it back).
    val firstRowFocus = remember { FocusRequester() }
    var focusedFirstRow by rememberSaveable { mutableStateOf(false) }
    val firstKey = state.rows.firstOrNull()?.app?.packageName
    LaunchedEffect(tv, firstKey) {
        if (tv && firstKey != null && !focusedFirstRow) {
            focusedFirstRow = runCatching { firstRowFocus.requestFocus() }.isSuccess
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appster") },
                actions = {
                    val source = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = vm::refresh,
                        interactionSource = source,
                        modifier = Modifier.focusRing(source, CircleShape),
                    ) { Icon(Icons.Default.Refresh, "Refresh") }
                },
            )
        },
    ) { padding ->
        val list = @Composable {
            LazyColumn(
                // TVs crop the screen edges (overscan), so keep content well inside them.
                contentPadding = if (tv) PaddingValues(horizontal = 48.dp, vertical = 16.dp) else PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Rows stay readable on a wide screen instead of stretching edge to edge.
                val rowWidth = Modifier.widthIn(max = 960.dp).fillMaxWidth()
                if (state.needsInstallPermission) {
                    item {
                        Notice(
                            text = "Allow Appster to install apps, then try again.",
                            action = "Allow" to onAllowInstalls,
                            dismiss = vm::dismissInstallPermission,
                            modifier = rowWidth,
                        )
                    }
                }
                state.error?.let {
                    item { Notice(text = "Couldn't load the store: $it", action = "Retry" to vm::refresh, modifier = rowWidth) }
                }
                if (state.updates.size > 1) {
                    item {
                        Row(
                            rowWidth.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${state.updates.size} updates available", Modifier.weight(1f))
                            val source = remember { MutableInteractionSource() }
                            FilledTonalButton(
                                onClick = vm::updateAll,
                                interactionSource = source,
                                modifier = Modifier.focusRing(source, ButtonDefaults.shape),
                            ) { Text("Update all") }
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
                    val onInstall = { vm.install(row.app.packageName) }
                    val onOpen = { onOpenApp(row.app.packageName) }
                    if (tv) {
                        val focus = if (row.app.packageName == firstKey) Modifier.focusRequester(firstRowFocus) else Modifier
                        TvAppRow(row, onInstall, onOpen, rowWidth.then(focus))
                    } else {
                        Column(rowWidth) {
                            AppRowItem(row, onInstall, onOpen)
                            HorizontalDivider(Modifier.padding(start = 80.dp))
                        }
                    }
                }
            }
        }

        if (tv) {
            // No pull-to-refresh on a TV: there's no touch to pull with, and D-pad presses at the
            // top of the list would drag the indicator instead of moving between apps.
            Box(Modifier.padding(padding).fillMaxSize()) {
                list()
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        } else {
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) { list() }
        }
    }
}

@Composable
private fun isTv(): Boolean =
    LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

/** A clear outline and slight zoom while focused, so the D-pad position is visible from the couch. */
@Composable
private fun Modifier.focusRing(source: MutableInteractionSource, shape: Shape): Modifier {
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focusScale")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
}

/**
 * On a TV each app is one focusable card: D-pad up/down moves between apps and OK runs the row's
 * action (install, update or open). That's simpler than reaching a small button inside each row.
 */
@Composable
private fun TvAppRow(row: AppRow, onInstall: () -> Unit, onOpen: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = { primaryAction(row, onInstall, onOpen)?.invoke() },
        shape = shape,
        color = if (focused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = source,
        modifier = modifier.focusRing(source, shape),
    ) {
        AppRowContent(row, Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            ActionLabel(row)
        }
    }
}

private fun primaryAction(row: AppRow, onInstall: () -> Unit, onOpen: () -> Unit): (() -> Unit)? = when {
    row.busy != null || !row.compatible -> null
    row.installed == null || row.updateAvailable -> onInstall
    else -> onOpen
}

@Composable
private fun AppRowItem(row: AppRow, onInstall: () -> Unit, onOpen: () -> Unit) {
    AppRowContent(row, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        ActionButton(row, onInstall, onOpen)
    }
}

@Composable
private fun AppRowContent(row: AppRow, modifier: Modifier, action: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = row.app
    Column(modifier.fillMaxWidth()) {
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
            action()
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

/** The TV row's action, drawn like a button but not focusable itself: the whole row takes OK. */
@Composable
private fun ActionLabel(row: AppRow) {
    when {
        row.busy is Busy.Installing || row.busy is Busy.WaitingForConfirmation ->
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        row.busy is Busy.Downloading -> Text("${((row.busy as Busy.Downloading).progress * 100).toInt()}%")
        !row.compatible -> {}
        else -> {
            val open = row.installed != null && !row.updateAvailable
            val label = when {
                row.installed == null -> "Install"
                row.updateAvailable -> "Update"
                else -> "Open"
            }
            Surface(
                shape = CircleShape,
                color = if (open) Color.Transparent else MaterialTheme.colorScheme.primary,
                contentColor = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                border = if (open) ButtonDefaults.outlinedButtonBorder() else null,
            ) {
                Text(label, Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>, dismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismiss?.let { NoticeButton("Not now", it) }
                NoticeButton(action.first, action.second)
            }
        }
    }
}

@Composable
private fun NoticeButton(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        interactionSource = source,
        modifier = Modifier.focusRing(source, ButtonDefaults.textShape),
    ) { Text(label) }
}
