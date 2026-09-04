package com.wuwa.gachatool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private object ResourceIcons {
    private const val BASE = "https://static.nanoka.cc"
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val catalogMutex = Mutex()
    private val memory = object : LruCache<Long, Bitmap>(24) {}
    private var iconPaths: Map<Long, String>? = null

    suspend fun load(context: Context, resourceId: Long): Bitmap? = withContext(Dispatchers.IO) {
        ResourcePack.iconFile(context, resourceId)?.let(::decode)?.let { memory.put(resourceId, it); return@withContext it }
        ResourcePack.refresh(context)
        ResourcePack.iconFile(context, resourceId)?.let(::decode)?.let { memory.put(resourceId, it); return@withContext it }
        memory.get(resourceId)?.let { return@withContext it }
        val directory = File(context.cacheDir, "resource-icons").apply { mkdirs() }
        val cached = File(directory, "$resourceId.webp")
        decode(cached)?.let { memory.put(resourceId, it); return@withContext it }
        val path = catalog()[resourceId] ?: return@withContext null
        if (!path.startsWith("/Game/Aki/UI/") || path.contains("..")) return@withContext null
        val assetPath = path.removePrefix("/Game/Aki/UI").substringBefore('.')
        val bytes = requestBytes("$BASE/assets/ww$assetPath.webp", 2 * 1024 * 1024) ?: return@withContext null
        if (bytes.size < 12 || !bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) || !bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return@withContext null
        val temporary = File(directory, "$resourceId.webp.part")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(cached)) { temporary.delete(); return@withContext null }
        decode(cached)?.also { memory.put(resourceId, it) }
    }

    private suspend fun catalog(): Map<Long, String> = catalogMutex.withLock {
        iconPaths?.let { return@withLock it }
        val manifest = requestJson("$BASE/manifest.json") ?: return@withLock emptyMap()
        val version = manifest.optJSONObject("ww")?.optString("latest").orEmpty()
        if (!version.matches(Regex("[A-Za-z0-9._+-]+"))) return@withLock emptyMap()
        val result = mutableMapOf<Long, String>()
        for (kind in listOf("character", "weapon")) {
            val entries = requestJson("$BASE/ww/$version/$kind.json") ?: continue
            for (key in entries.keys()) {
                key.toLongOrNull()?.let { id -> entries.optJSONObject(key)?.optString("icon")?.takeIf(String::isNotBlank)?.let { result[id] = it } }
            }
        }
        result.toMap().also { iconPaths = it }
    }

    private fun requestJson(url: String): JSONObject? = requestBytes(url, 5 * 1024 * 1024)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
    private fun requestBytes(url: String, limit: Int): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > limit) return@use null
            body.bytes().takeIf { it.size <= limit }
        }
    }.getOrNull()
    private fun decode(file: File): Bitmap? = if (file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
}

@Composable
fun ResourceAvatar(record: GachaRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, record.resourceId) { value = ResourceIcons.load(context, record.resourceId) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2F34)).semantics { contentDescription = "${record.name}头像" }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.size(44.dp), contentScale = ContentScale.Crop)
        else Text(record.name.take(1), color = Color(0xFFAEB1AA), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
