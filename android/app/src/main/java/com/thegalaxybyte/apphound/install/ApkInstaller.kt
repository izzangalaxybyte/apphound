package com.thegalaxybyte.apphound.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed interface InstallResult {
    val packageName: String

    data class Success(override val packageName: String) : InstallResult
    data class Failure(override val packageName: String, val message: String) : InstallResult
    /** Android wants the user to confirm; the confirmation screen has been opened. */
    data class NeedsConfirmation(override val packageName: String) : InstallResult
}

object ApkInstaller {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 16)
    val results: SharedFlow<InstallResult> = _results

    internal fun emit(result: InstallResult) {
        _results.tryEmit(result)
    }

    /** Whether the user has allowed this app to install apps ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Installs or updates [packageName] from [apk] with a PackageInstaller session. The outcome
     * arrives on [results] via [InstallResultReceiver].
     *
     * Asks Android to skip the confirmation prompt (Android 12+). That's honoured only for updates
     * of apps this store installed, which is why first installs also claim update ownership.
     */
    suspend fun install(context: Context, packageName: String, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_APK_PATH, apk.absolutePath)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Throwable) {
            installer.abandonSession(sessionId)
            throw e
        }
    }
}
