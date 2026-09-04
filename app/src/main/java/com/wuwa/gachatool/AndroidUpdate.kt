package com.wuwa.gachatool

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AndroidUpdate(val version: String, val downloadUrl: String, val size: Long)

object AndroidUpdateService {
    private const val API = "https://api.github.com/repos/juliy819/wuwa-gacha-tool-android/releases/latest"
    private val sources = listOf(
        API,
        "https://ghproxy.net/$API",
        "https://hk.gh-proxy.org/$API",
        "https://cdn.gh-proxy.org/$API",
        "https://api.github.com/repos/juliy819/wuwa-gacha-tool-android/releases/latest",
    )

    suspend fun check(): AndroidUpdate? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().build()
        for (source in sources.distinct()) {
            runCatching {
                val request = Request.Builder().url(source).header("Accept", "application/vnd.github+json").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val json = JSONObject(response.body?.string() ?: error("empty response"))
                    val version = json.optString("tag_name").removePrefix("v")
                    if (version.isBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) return@use null
                    val assets = json.optJSONArray("assets") ?: return@use null
                    for (index in 0 until assets.length()) {
                        val asset = assets.getJSONObject(index)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            return@withContext AndroidUpdate(version, asset.optString("browser_download_url"), asset.optLong("size"))
                        }
                    }
                    null
                }
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    fun openDownload(context: Context, update: AndroidUpdate) {
        val encoded = update.downloadUrl.replace("https://github.com/", "https://ghproxy.net/https://github.com/")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(encoded)))
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        return (0..2).firstNotNullOfOrNull { index ->
            when {
                (r.getOrElse(index) { 0 }) > l.getOrElse(index) { 0 } -> true
                (r.getOrElse(index) { 0 }) < l.getOrElse(index) { 0 } -> false
                else -> null
            }
        } ?: false
    }
}
