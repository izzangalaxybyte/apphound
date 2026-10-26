package com.thegalaxybyte.apphound

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thegalaxybyte.apphound.data.InstalledApp
import com.thegalaxybyte.apphound.data.InstalledApps
import com.thegalaxybyte.apphound.data.RemoteApp
import com.thegalaxybyte.apphound.data.StoreApi
import com.thegalaxybyte.apphound.install.ApkDownloader
import com.thegalaxybyte.apphound.install.ApkInstaller
import com.thegalaxybyte.apphound.install.InstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the row is doing right now, if anything. */
sealed interface Busy {
    data class Downloading(val progress: Float) : Busy
    data object Installing : Busy
    data object WaitingForConfirmation : Busy
}

data class AppRow(
    val app: RemoteApp,
    val installed: InstalledApp?,
    val busy: Busy? = null,
    val error: String? = null,
) {
    val compatible get() = Build.VERSION.SDK_INT >= app.minSdk
    val updateAvailable get() = installed != null && app.versionCode > installed.versionCode
}

data class StoreState(
    val loading: Boolean = false,
    val error: String? = null,
    val rows: List<AppRow> = emptyList(),
    /** Set when an install was tapped but "Install unknown apps" isn't allowed yet. */
    val needsInstallPermission: Boolean = false,
) {
    val updates get() = rows.filter { it.updateAvailable && it.compatible && it.busy == null }
}

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state

    init {
        refresh()
        viewModelScope.launch {
            ApkInstaller.results.collect { result ->
                when (result) {
                    is InstallResult.Success -> setRow(result.packageName) {
                        it.copy(busy = null, error = null, installed = InstalledApps.get(context, it.app.packageName))
                    }
                    is InstallResult.Failure -> setRow(result.packageName) { it.copy(busy = null, error = result.message) }
                    is InstallResult.NeedsConfirmation -> setRow(result.packageName) { it.copy(busy = Busy.WaitingForConfirmation) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val apps = StoreApi.listApps()
                val busy = _state.value.rows.associate { it.app.packageName to it.busy }
                val rows = apps.map { AppRow(it, InstalledApps.get(context, it.packageName), busy[it.packageName]) }
                _state.update { it.copy(loading = false, rows = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Re-reads installed versions, e.g. when returning to the app after an uninstall. */
    fun refreshInstalled() {
        _state.update { s ->
            s.copy(
                rows = s.rows.map { it.copy(installed = InstalledApps.get(context, it.app.packageName)) },
                needsInstallPermission = s.needsInstallPermission && !ApkInstaller.canInstall(context),
            )
        }
    }

    fun install(packageName: String) {
        if (!ApkInstaller.canInstall(context)) {
            _state.update { it.copy(needsInstallPermission = true) }
            return
        }
        val row = _state.value.rows.find { it.app.packageName == packageName } ?: return
        if (row.busy != null) return
        setRow(packageName) { it.copy(busy = Busy.Downloading(0f), error = null) }
        viewModelScope.launch {
            try {
                // Fetch again: the list's download URLs may have expired while the screen was open.
                val latest = StoreApi.latest(packageName)
                val apk = ApkDownloader.download(context, latest) { p ->
                    setRow(packageName) { it.copy(busy = Busy.Downloading(p)) }
                }
                setRow(packageName) { it.copy(app = latest, busy = Busy.Installing) }
                ApkInstaller.install(context, packageName, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setRow(packageName) { it.copy(busy = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun updateAll() {
        // The store itself last: installing its update restarts this process.
        state.value.updates
            .sortedBy { it.app.packageName == context.packageName }
            .forEach { install(it.app.packageName) }
    }

    fun dismissInstallPermission() = _state.update { it.copy(needsInstallPermission = false) }

    private fun setRow(packageName: String, change: (AppRow) -> AppRow) {
        _state.update { s -> s.copy(rows = s.rows.map { if (it.app.packageName == packageName) change(it) else it }) }
    }
}
