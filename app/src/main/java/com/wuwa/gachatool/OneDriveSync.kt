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

/**
 * 取云端快照是网络操作，期间本机可能被导入/编辑改写；每次决策前都要重新
 * 生成本机快照确认没有变化，连续变化时最多重决策这么多次。
 */
private const val MAX_SYNC_RESTARTS = 3

data class OneDriveStatus(val configured: Boolean, val connected: Boolean, val loginPending: Boolean)
data class DeviceLoginInfo(val userCode: String, val verificationUri: String, val expiresAt: Instant, val intervalSeconds: Long)
data class CloudSyncResult(
    val addedCount: Int,
    val duplicateCount: Int,
    val totalCount: Int,
    val uploadedCount: Int,
    val conflictRetries: Int,
    val updatedAt: String,
    /** 本次用云端快照覆盖本机时丢掉的本机记录数（0 表示没有丢）。 */
    val lostCount: Int = 0,
    /** 被丢弃记录的时间范围，用于向用户说明影响。 */
    val lostEarliest: String? = null,
    val lostLatest: String? = null,
    /** 覆盖前自动留下的本机整库备份路径。 */
    val backupPath: String? = null,
)
data class CloudImportResult(
    val uids: List<String>,
    val addedCount: Int,
    val totalCount: Int,
    val failures: List<String>,
    /** 云端覆盖本机时丢掉的本机记录数（0 表示没有丢）。 */
    val lostCount: Int = 0,
    val lostEarliest: String? = null,
    val lostLatest: String? = null,
    /** 覆盖前自动留下的本机整库备份路径。 */
    val backupPath: String? = null,
)

/**
 * 云同步的方向决策。输入全部是“同步窗口内的当前状态”，函数本身不做任何 IO，
 * 便于用单测覆盖全部分支；任何会覆盖本机的写入都必须发生在这个决策之后的
 * 同一次执行里（并已确认本机没有变化）。
 */
internal enum class SyncAction {
    /** 两端自上次同步以来都没有变化，本机数据库不需要改动。 */
    Keep,

    /** 云端有更新、本机没变：用云端快照覆盖本机。 */
    ApplyRemote,

    /** 本机有更新、云端没变：把本机快照推送到云端。 */
    UploadLocal,

    /** 首次连接且两端都有数据，无法自动判断该保留哪一端。 */
    FirstConnectConflict,

    /** 两端在最后一次同步之后都发生了变化，交给用户选择。 */
    BothChangedConflict,
}

internal fun decideSyncAction(
    baselineEtag: String?,
    baselineHash: String?,
    localHash: String,
    localCount: Int,
    remoteEtag: String,
    strategy: String?,
): SyncAction {
    val remoteChanged = baselineEtag != remoteEtag
    val localChanged = baselineHash != localHash
    val explicit = strategy == "local" || strategy == "remote"
    if (baselineEtag == null && localCount > 0 && !explicit) return SyncAction.FirstConnectConflict
    if (baselineEtag != null && remoteChanged && localChanged && !explicit) return SyncAction.BothChangedConflict
    return when (strategy) {
        "remote" -> SyncAction.ApplyRemote
        "local" -> SyncAction.UploadLocal
        // 两端都没变时不再无脑重放云端快照：那是一次没有任何收益、
        // 却足以抹掉同步窗口内新写入记录的整库覆盖。
        else -> when {
            !remoteChanged && !localChanged -> SyncAction.Keep
            !remoteChanged && localChanged -> SyncAction.UploadLocal
            remoteChanged && !localChanged -> SyncAction.ApplyRemote
            // 只剩“首次连接且本机为空”这一种情况：以云端为准拉取。
            else -> SyncAction.ApplyRemote
        }
    }
}
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

    suspend fun sync(uid: String, strategy: String? = null): CloudSyncResult = operationMutex.withLock {
        val token = accessToken()
        ensureDirectories(token)
        syncDatabase(token, strategy)
    }

    suspend fun syncAll(localUids: List<String>, strategy: String? = null): CloudImportResult = operationMutex.withLock {
        val token = accessToken(); ensureDirectories(token)
        val result = syncDatabase(token, strategy)
        CloudImportResult(
            repository.localUids(),
            result.addedCount,
            result.totalCount,
            emptyList(),
            lostCount = result.lostCount,
            lostEarliest = result.lostEarliest,
            lostLatest = result.lostLatest,
            backupPath = result.backupPath,
        )
    }

    private suspend fun syncDatabase(token: String, strategy: String? = null): CloudSyncResult {
        val localFile = File.createTempFile("gacha-local-", ".db", appContext.cacheDir)
        val remoteFile = File.createTempFile("gacha-remote-", ".db", appContext.cacheDir)
        val verifyFile = File.createTempFile("gacha-verify-", ".db", appContext.cacheDir)
        try {
            // 1) 本机快照 + 上次同步基线。之后下载云端快照可能耗时数秒，
            //    这段时间里本机仍可被导入改写，因此这份状态不能直接当结论用。
            repository.createSnapshot(localFile)
            val baselineEtag = syncState.getString("etag", null)
            val baselineHash = syncState.getString("snapshot_sha256", null)
            var localHash = sha256(localFile.readBytes())
            var localCount = repository.recordCount()
            val cloud = downloadSnapshot(token)
            if (cloud == null) {
                val etag = uploadSnapshot(token, localFile.readBytes(), null)
                saveBaseline(etag, localHash)
                return CloudSyncResult(0, 0, localCount, localCount, 0, Instant.now().toString())
            }
            remoteFile.writeBytes(cloud.bytes)

            var attempt = 0
            while (attempt < MAX_SYNC_RESTARTS) {
                // 2) 覆盖本机不可逆：先用“此刻”的本机快照确认同步窗口内没有变化。
                repository.createSnapshot(verifyFile)
                val currentHash = sha256(verifyFile.readBytes())
                if (currentHash != localHash) {
                    // 本机变了（导入/编辑/删除）：旧判断作废，用新状态重新决策。
                    localHash = currentHash
                    localCount = repository.recordCount()
                    attempt += 1
                    continue
                }
                when (decideSyncAction(baselineEtag, baselineHash, localHash, localCount, cloud.etag, strategy)) {
                    SyncAction.Keep -> return CloudSyncResult(0, 0, localCount, localCount, 0, Instant.now().toString())

                    SyncAction.ApplyRemote -> {
                        val preview = repository.previewApply(remoteFile)
                        // 覆盖前必须留底：整库替换没有任何撤销手段。
                        val backup = if (localCount > 0) repository.backupBeforeApply() else null
                        val total = repository.applySnapshot(remoteFile)
                        repository.createSnapshot(localFile)
                        saveBaseline(cloud.etag, sha256(localFile.readBytes()))
                        return CloudSyncResult(
                            addedCount = (total - localCount).coerceAtLeast(0),
                            duplicateCount = 0,
                            totalCount = total,
                            uploadedCount = 0,
                            conflictRetries = 0,
                            updatedAt = Instant.now().toString(),
                            lostCount = preview.lostCount,
                            lostEarliest = preview.lostEarliest,
                            lostLatest = preview.lostLatest,
                            backupPath = backup?.absolutePath,
                        )
                    }

                    SyncAction.UploadLocal -> {
                        // 上传的是刚校验过的这份内容；若上传期间本机又有新数据，
                        // 基线记录的仍是真正上传的哈希，下次同步会重新上传，
                        // 而不是被误判成“已同步”。
                        val etag = uploadSnapshot(token, verifyFile.readBytes(), cloud.etag)
                        saveBaseline(etag, localHash)
                        return CloudSyncResult(0, 0, localCount, localCount, 0, Instant.now().toString())
                    }

                    SyncAction.FirstConnectConflict -> error("本机和云端都已有数据，首次连接时无法判断应保留哪一版；请先保留其中一端的数据")

                    SyncAction.BothChangedConflict -> error("本机和云端数据库都已发生变化。为避免覆盖，请先保留其中一端的修改后再同步")
                }
            }
            error("本机数据在同步过程中持续变化，已停止本次云同步，请稍后重试")
        } finally {
            localFile.delete(); remoteFile.delete(); verifyFile.delete()
        }
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
