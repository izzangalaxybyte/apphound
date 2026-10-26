package com.thegalaxybyte.apphound.install

import android.content.Context
import com.thegalaxybyte.apphound.data.RemoteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ApkDownloader {
    /**
     * Downloads [app]'s APK into the app's cache and checks it against the manifest's SHA-256,
     * so a corrupted or swapped file is never handed to the installer.
     */
    suspend fun download(context: Context, app: RemoteApp, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "apks").apply { mkdirs() }
            val file = File(dir, "${app.packageName}-${app.versionCode}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = URL(app.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode != 200) throw IOException("download failed: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: app.size
                var read = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
            } catch (e: Throwable) {
                file.delete()
                throw e
            } finally {
                conn.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(app.sha256, ignoreCase = true)) {
                file.delete()
                throw IOException("checksum mismatch for ${app.packageName}; not installing")
            }
            file
        }

    fun clear(context: Context) {
        File(context.cacheDir, "apks").deleteRecursively()
    }
}
