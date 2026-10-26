package com.thegalaxybyte.apphound.data

import com.thegalaxybyte.apphound.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One app as the apphound API describes it: its latest published version. */
data class RemoteApp(
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val size: Long,
    val sha256: String,
    val changelog: String,
    val apkUrl: String,
    val iconUrl: String?,
)

/** Client for the apphound Lambda API. Download URLs it returns expire after ~15 minutes. */
object StoreApi {
    suspend fun listApps(): List<RemoteApp> = withContext(Dispatchers.IO) {
        val apps = JSONObject(get("/apps")).getJSONArray("apps")
        (0 until apps.length()).map { parse(apps.getJSONObject(it)) }
    }

    suspend fun latest(packageName: String): RemoteApp = withContext(Dispatchers.IO) {
        parse(JSONObject(get("/apps/$packageName")))
    }

    private fun get(path: String): String {
        check(BuildConfig.API_URL.isNotEmpty()) { "API URL missing: run scripts/deploy.sh, then rebuild" }
        val conn = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.API_TOKEN}")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("API returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(o: JSONObject) = RemoteApp(
        packageName = o.getString("packageName"),
        name = o.getString("name"),
        versionCode = o.getLong("versionCode"),
        versionName = o.optString("versionName"),
        minSdk = o.optInt("minSdk", 1),
        size = o.optLong("size"),
        sha256 = o.getString("sha256"),
        changelog = o.optString("changelog"),
        apkUrl = o.getString("apkUrl"),
        iconUrl = o.optString("iconUrl").takeIf { it.isNotEmpty() && it != "null" },
    )
}
