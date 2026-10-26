package com.thegalaxybyte.apphound.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import java.io.File

/** Receives PackageInstaller session results and forwards them to [ApkInstaller.results]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ApkInstaller.emit(InstallResult.NeedsConfirmation(pkg))
                } else {
                    ApkInstaller.emit(InstallResult.Failure(pkg, "installer asked for confirmation but gave no screen"))
                }
                return  // the session isn't finished; the final status arrives later
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.emit(InstallResult.Success(pkg))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else "install failed ($status)"
                ApkInstaller.emit(InstallResult.Failure(pkg, message))
            }
        }
        intent.getStringExtra(EXTRA_APK_PATH)?.let { File(it).delete() }
    }

    companion object {
        const val EXTRA_APK_PATH = "com.thegalaxybyte.apphound.APK_PATH"
    }
}
