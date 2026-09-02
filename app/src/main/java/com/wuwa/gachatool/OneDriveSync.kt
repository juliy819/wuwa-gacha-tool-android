package com.wuwa.gachatool

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AUTH_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0/me/drive/root"
private const val SCOPE = "offline_access Files.ReadWrite"
private const val SYNC_ROOT_NAME = "Wuwa Gacha Tool"
private const val MAX_SYNC_BYTES = 256 * 1024 * 1024
private const val SYNC_DATABASE_NAME = "gacha-data.db"

data class OneDriveStatus(val configured: Boolean, val connected: Boolean, val loginPending: Boolean)
data class DeviceLoginInfo(val userCode: String, val verificationUri: String, val expiresAt: Instant, val intervalSeconds: Long)
data class CloudSyncResult(val addedCount: Int, val duplicateCount: Int, val totalCount: Int, val uploadedCount: Int, val conflictRetries: Int, val updatedAt: String)
data class CloudImportResult(val uids: List<String>, val addedCount: Int, val totalCount: Int, val failures: List<String>)
enum class LoginPollResult { PENDING, CONNECTED }

private data class PendingLogin(val deviceCode: String, val info: DeviceLoginInfo)
private data class AccessToken(val value: String, val expiresAt: Instant)
private data class CloudSnapshot(val etag: String, val bytes: ByteArray)
private data class HttpResult(val code: Int, val successful: Boolean, val body: ByteArray, val contentLength: Long)

class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("onedrive_credentials", Context.MODE_PRIVATE)
    private val keyAlias = "wuwa-gacha-tool-onedrive"

    fun hasRefreshToken(): Boolean = preferences.contains("refresh_token")

    fun readRefreshToken(): String? = runCatching {
        val packed = Base64.decode(preferences.getString("refresh_token", null), Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }.getOrNull()

    fun saveRefreshToken(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("refresh_token", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "无法保存 OneDrive 登录凭据"
        }
    }

    fun clear() {
        preferences.edit().remove("refresh_token").commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}

class OneDriveSyncService(context: Context, private val repository: SyncRepository) {
    private val appContext = context.applicationContext
    private val syncState = appContext.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val tokenStore = SecureTokenStore(context.applicationContext)
    private val operationMutex = Mutex()
    private var pending: PendingLogin? = null
    private var accessToken: AccessToken? = null

    fun status() = OneDriveStatus(BuildConfig.ONEDRIVE_CLIENT_ID.isNotBlank(), tokenStore.hasRefreshToken(), pending != null)

    suspend fun startLogin(): DeviceLoginInfo = operationMutex.withLock {
        requireConfigured()
        val body = FormBody.Builder().add("client_id", BuildConfig.ONEDRIVE_CLIENT_ID).add("scope", SCOPE).build()
        val json = executeJson(Request.Builder().url("$AUTH_BASE/devicecode").post(body).build(), "无法开始 OneDrive 登录")
        val info = DeviceLoginInfo(
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresAt = Instant.now().plusSeconds(json.getLong("expires_in")),
            intervalSeconds = json.optLong("interval", 5).coerceAtLeast(1),
        )
        pending = PendingLogin(json.getString("device_code"), info)
        info
    }

    suspend fun pollLogin(): LoginPollResult = operationMutex.withLock {
        val current = pending ?: error("没有待完成的 OneDrive 登录")
        if (!Instant.now().isBefore(current.info.expiresAt)) {
            pending = null
            error("OneDrive 登录验证码已过期，请重新登录")
        }
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
            .add("device_code", current.deviceCode)
            .build()
        val response = execute(Request.Builder().url("$AUTH_BASE/token").post(body).build())
        run {
            val text = response.body.toString(Charsets.UTF_8)
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!response.successful) {
                return@withLock when (json.optString("error")) {
                    "authorization_pending", "slow_down" -> LoginPollResult.PENDING
                    "expired_token" -> { pending = null; error("OneDrive 登录验证码已过期，请重新登录") }
                    "access_denied" -> { pending = null; error("OneDrive 登录已取消") }
                    else -> error(json.optString("error_description", "OneDrive 登录失败"))
                }
            }
            storeTokens(json)
            pending = null
            LoginPollResult.CONNECTED
        }
    }

    fun disconnect() {
        pending = null
        accessToken = null
        tokenStore.clear()
    }

    fun cancelLogin() {
        pending = null
    }

    suspend fun sync(uid: String): CloudSyncResult = operationMutex.withLock {
        val token = accessToken()
        ensureDirectories(token)
        syncDatabase(token)
    }

    suspend fun syncAll(localUids: List<String>): CloudImportResult = operationMutex.withLock {
        val token = accessToken(); ensureDirectories(token)
        val result = syncDatabase(token)
        CloudImportResult(repository.localUids(), result.addedCount, result.totalCount, emptyList())
    }

    private suspend fun syncDatabase(token: String): CloudSyncResult {
        val localFile = File.createTempFile("gacha-local-", ".db", appContext.cacheDir)
        val remoteFile = File.createTempFile("gacha-remote-", ".db", appContext.cacheDir)
        try {
            repository.createSnapshot(localFile)
            val before = repository.recordCount(); val localHash = sha256(localFile.readBytes())
            val baselineEtag = syncState.getString("etag", null); val baselineHash = syncState.getString("snapshot_sha256", null)
            val cloud = downloadSnapshot(token)
            if (cloud == null) {
                val etag = uploadSnapshot(token, localFile.readBytes(), null)
                saveBaseline(etag, localHash)
                return CloudSyncResult(0, 0, before, before, 0, Instant.now().toString())
            }
            remoteFile.writeBytes(cloud.bytes)
            val remoteChanged = baselineEtag != cloud.etag; val localChanged = baselineHash != localHash
            if (baselineEtag == null && before > 0) error("本机和云端都已有数据，首次连接时无法判断应保留哪一版；请先保留其中一端的数据")
            if (baselineEtag != null && remoteChanged && localChanged) error("本机和云端数据库都已发生变化。为避免覆盖，请先保留其中一端的修改后再同步")
            if (!remoteChanged && localChanged) {
                val etag = uploadSnapshot(token, localFile.readBytes(), cloud.etag); saveBaseline(etag, localHash)
                return CloudSyncResult(0, 0, before, before, 0, Instant.now().toString())
            }
            val total = repository.applySnapshot(remoteFile)
            repository.createSnapshot(localFile); saveBaseline(cloud.etag, sha256(localFile.readBytes()))
            return CloudSyncResult((total-before).coerceAtLeast(0), 0, total, 0, 0, Instant.now().toString())
        } finally { localFile.delete(); remoteFile.delete() }
    }

    private fun saveBaseline(etag:String, hash:String) { check(syncState.edit().putString("etag",etag).putString("snapshot_sha256",hash).commit()) }
    private fun sha256(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun accessToken(): String {
        accessToken?.takeIf { Instant.now().plusSeconds(60).isBefore(it.expiresAt) }?.let { return it.value }
        val refresh = tokenStore.readRefreshToken() ?: error("请先登录 OneDrive")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
            .add("refresh_token", refresh)
            .add("scope", SCOPE)
            .build()
        val json = executeJson(Request.Builder().url("$AUTH_BASE/token").post(body).build(), "OneDrive 登录已失效，请重新登录")
        storeTokens(json)
        return json.getString("access_token")
    }

    private fun storeTokens(json: JSONObject) {
        json.optString("refresh_token").takeIf(String::isNotBlank)?.let(tokenStore::saveRefreshToken)
        accessToken = AccessToken(json.getString("access_token"), Instant.now().plusSeconds(json.getLong("expires_in")))
    }

    private suspend fun ensureDirectories(token: String) {
        ensureFolder(token, GRAPH_BASE, SYNC_ROOT_NAME)
    }

    private suspend fun ensureFolder(token: String, parent: String, name: String) {
        val body = JSONObject().put("name", name).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail")
            .toString().toRequestBody("application/json".toMediaType())
        val response = execute(Request.Builder().url("$parent/children").header("Authorization", "Bearer $token").post(body).build())
        if (!response.successful && response.code != 409) graphError(response.code, response.body.toString(Charsets.UTF_8), "无法创建 OneDrive 同步目录")
    }

    private suspend fun downloadSnapshot(token: String): CloudSnapshot? {
        val path = "$GRAPH_BASE:/$SYNC_ROOT_NAME/$SYNC_DATABASE_NAME"
        val metadata = execute(Request.Builder().url(path).header("Authorization", "Bearer $token").get().build())
        val etag = run {
            if (metadata.code == 404) return null
            val text = metadata.body.toString(Charsets.UTF_8)
            if (!metadata.successful) graphError(metadata.code, text, "无法读取 OneDrive 同步元数据")
            JSONObject(text).getString("eTag")
        }
        val content = execute(Request.Builder().url("$path:/content").header("Authorization", "Bearer $token").get().build())
        return run {
            if (!content.successful) graphError(content.code, content.body.toString(Charsets.UTF_8), "无法下载 OneDrive 同步数据")
            require(content.contentLength < 0 || content.contentLength <= MAX_SYNC_BYTES) { "云端同步数据超过大小限制" }
            require(content.body.size <= MAX_SYNC_BYTES) { "云端同步数据超过大小限制" }
            CloudSnapshot(etag, content.body)
        }
    }

    private suspend fun uploadSnapshot(token: String, bytes: ByteArray, etag: String?): String {
        require(bytes.size <= MAX_SYNC_BYTES) { "同步数据超过大小限制" }
        val builder = Request.Builder().url("$GRAPH_BASE:/$SYNC_ROOT_NAME/$SYNC_DATABASE_NAME:/content")
            .header("Authorization", "Bearer $token")
            .header(if (etag == null) "If-None-Match" else "If-Match", etag ?: "*")
            .put(bytes.toRequestBody("application/vnd.sqlite3".toMediaType()))
        val response = execute(builder.build())
        if (response.code == 409 || response.code == 412) error("云端数据库已变化，请重新同步")
        if (!response.successful) graphError(response.code, response.body.toString(Charsets.UTF_8), "无法上传 OneDrive 同步数据")
        return JSONObject(response.body.toString(Charsets.UTF_8)).getString("eTag")
    }

    private suspend fun execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body
            HttpResult(response.code, response.isSuccessful, body?.bytes() ?: ByteArray(0), body?.contentLength() ?: -1)
        }
    }

    private suspend fun executeJson(request: Request, fallback: String): JSONObject = execute(request).let {
        val text = it.body.toString(Charsets.UTF_8)
        if (!it.successful) graphError(it.code, text, fallback)
        runCatching { JSONObject(text) }.getOrElse { error("Microsoft 服务响应格式无效") }
    }

    private fun graphError(code: Int, body: String, fallback: String): Nothing {
        if (code == 401) error("OneDrive 登录已失效，请重新登录")
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optJSONObject("error")?.optString("message")
            ?: json?.optString("error_description")
        error(message?.takeIf(String::isNotBlank) ?: "$fallback（HTTP $code）")
    }

    private fun requireConfigured() = require(BuildConfig.ONEDRIVE_CLIENT_ID.isNotBlank()) { "当前构建未配置 OneDrive Client ID" }
}
