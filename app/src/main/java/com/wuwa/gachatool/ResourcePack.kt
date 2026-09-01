package com.wuwa.gachatool

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ResourcePack {
    private const val MANIFEST_URL = "https://github.com/juliy819/wuwa-gacha-tool-resources/releases/latest/download/resource-manifest.json"
    private const val MAX_MANIFEST = 64 * 1024
    private const val MAX_ARCHIVE = 128 * 1024 * 1024
    private const val MAX_ENTRY = 2 * 1024 * 1024
    private const val MAX_EXTRACTED = 256 * 1024 * 1024L
    private const val RETRY_COOLDOWN_MS = 15 * 60 * 1000L
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var lastAttemptAt = 0L

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) { mutex.withLock { refreshLocked(context.applicationContext) } }
    fun iconFile(context: Context, id: Long): File? = File(context.filesDir, "resource-pack/icons/$id.webp").takeIf(::validWebp)

    private fun refreshLocked(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastAttemptAt != 0L && now - lastAttemptAt < RETRY_COOLDOWN_MS) return
        lastAttemptAt = now
        val manifestBytes = fetchWithFallback(MANIFEST_URL, MAX_MANIFEST, null) ?: return
        val manifest = runCatching { JSONObject(String(manifestBytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val archiveUrl = manifest.optString("archive_url")
        val archiveSha = manifest.optString("archive_sha256").lowercase()
        val archiveSize = manifest.optLong("archive_size")
        if (!validManifestUrl(archiveUrl) || !archiveSha.matches(Regex("[0-9a-f]{64}")) || archiveSize !in 1..MAX_ARCHIVE.toLong()) return
        val root = File(context.filesDir, "resource-pack")
        val installed = File(root, "manifest.json")
        if (installed.isFile && runCatching { JSONObject(installed.readText()).optString("archive_sha256") == archiveSha }.getOrDefault(false)) return
        val archive = fetchWithFallback(archiveUrl, MAX_ARCHIVE, archiveSha) ?: return
        if (archive.size.toLong() != archiveSize) return
        val staging = File(context.filesDir, "resource-pack.installing").apply { deleteRecursively(); mkdirs() }
        if (!extract(archive, staging) || !validatePack(staging)) { staging.deleteRecursively(); return }
        File(staging, "manifest.json").writeBytes(manifestBytes)
        val backup = File(context.filesDir, "resource-pack.previous").apply { deleteRecursively() }
        if (root.exists() && !root.renameTo(backup)) { staging.deleteRecursively(); return }
        if (!staging.renameTo(root)) { backup.renameTo(root); staging.deleteRecursively(); return }
        backup.deleteRecursively()
    }

    private fun fetchWithFallback(url: String, limit: Int, sha: String?): ByteArray? {
        for (candidate in sources(url)) fetch(candidate, limit)?.let { if (sha == null || sha256(it) == sha) return it }
        return null
    }

    private fun fetch(url: String, limit: Int): ByteArray? = runCatching { client.newCall(Request.Builder().url(url).get().build()).execute().use { response -> if (!response.isSuccessful) return@use null; val body=response.body ?: return@use null; if (body.contentLength()>limit) return@use null; body.bytes().takeIf { it.isNotEmpty() && it.size<=limit } } }.getOrNull()
    private fun sources(url: String): List<String> = if (!url.startsWith("https://github.com/")) listOf(url) else listOf("https://cors.isteed.cc/${url.removePrefix("https://")}", "https://hk.gh-proxy.org/$url", "https://cdn.gh-proxy.org/$url", "https://ghproxy.net/$url", "https://edgeone.gh-proxy.org/$url", url)

    private fun extract(bytes: ByteArray, dir: File): Boolean = runCatching {
        var count=0; var total=0L
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry=zip.nextEntry ?: break; count++; if(count>2048) error("too many entries"); val relative=entry.name.replace('\\','/').removePrefix("resource-pack/"); if(entry.isDirectory || relative.isBlank()) continue; if(!relative.matches(Regex("catalog\\.json|icons/[0-9]+\\.webp|portraits/[0-9]+\\.webp"))) error("unsafe entry"); val output=File(dir,relative); if(!output.canonicalPath.startsWith(dir.canonicalPath+File.separator)) error("unsafe path"); output.parentFile?.mkdirs(); FileOutputStream(output).use { stream -> val buffer=ByteArray(16384); var written=0; while(true){val read=zip.read(buffer); if(read<0)break; written+=read; total+=read; if(written>MAX_ENTRY || total>MAX_EXTRACTED)error("too large"); stream.write(buffer,0,read)}; if(written==0)error("empty") } } }
        count>0
    }.getOrDefault(false)

    private fun validatePack(dir: File): Boolean = runCatching { val catalog=JSONObject(File(dir,"catalog.json").readText()); val icons=catalog.optJSONObject("icons") ?: return@runCatching false; if(icons.length()==0)return@runCatching false; for(id in icons.keys()) if(!id.matches(Regex("[0-9]+")) || !validWebp(File(dir,"icons/$id.webp")))return@runCatching false; true }.getOrDefault(false)
    private fun validManifestUrl(url: String)=url.matches(Regex("https://github\\.com/juliy819/wuwa-gacha-tool-resources/releases/download/resources-[^/?#]+/resource-pack\\.zip"))
    private fun validWebp(file: File)=runCatching { file.isFile && file.length() in 12..MAX_ENTRY.toLong() && file.inputStream().use { val h=ByteArray(12); it.read(h)==12 && String(h,0,4)=="RIFF" && String(h,8,4)=="WEBP" } }.getOrDefault(false)
    private fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
