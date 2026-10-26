package com.thegalaxybyte.apphound.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

data class InstalledApp(
    val versionCode: Long,
    val versionName: String?,
    /** True when this store installed the app, so Android 12+ lets it update without a prompt. */
    val installedByStore: Boolean,
)

object InstalledApps {
    fun get(context: Context, packageName: String): InstalledApp? {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val installer = if (Build.VERSION.SDK_INT >= 34) {
            pm.getInstallSourceInfo(packageName).let { it.updateOwnerPackageName ?: it.installingPackageName }
        } else if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(packageName)
        }
        return InstalledApp(
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName,
            installedByStore = installer == context.packageName || packageName == context.packageName,
        )
    }
}
