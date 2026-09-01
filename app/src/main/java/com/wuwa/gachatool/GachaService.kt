package com.wuwa.gachatool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GachaService {
    private val standardCharacters = setOf(1104L, 1203L, 1301L, 1405L, 1503L)
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val pools = (1..13).map(Int::toString)

    data class FetchResult(val uid: String, val records: List<GachaRecord>, val failedPools: List<String>)

    suspend fun importFromUrl(rawUrl: String): FetchResult = withContext(Dispatchers.IO) {
        val params = ImportParser.params(rawUrl)
        val endpoint = if (params.uid.startsWith("1")) "https://gmserver-api.aki-game2.com/gacha/record/query" else "https://gmserver-api.aki-game2.net/gacha/record/query"
        val output = mutableListOf<GachaRecord>()
        val failures = mutableListOf<String>()
        for (pool in pools) {
            val body = JSONObject().apply {
                put("playerId", params.uid); put("recordId", params.recordId); put("cardPoolId", params.resourcesId)
                put("serverId", params.serverId); put("languageCode", params.lang); put("cardPoolType", pool)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(endpoint).post(body).header("Content-Type", "application/json").build()
            runCatching { client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                if (root.optInt("code", -1) != 0) error("错误码 ${root.optInt("code")}")
                val data = root.optJSONArray("data") ?: return@use
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val resourceId = item.optLong("resourceId")
                    val quality = item.optInt("qualityLevel")
                    val type = when (item.optString("resourceType")) { "角色" -> "role"; "武器" -> "weapon"; else -> if (resourceId < 100000) "role" else "weapon" }
                    val count = item.optInt("count", 1)
                    val time = item.optString("time")
                    val actualPool = ImportParser.poolIdFromApiName(item.optString("cardPoolType")) ?: pool
                    val offRate = quality == 5 && type == "role" && actualPool in setOf("1", "8", "10", "12") && resourceId in standardCharacters
                    output += GachaRecord(uid = params.uid, pool = actualPool, poolName = ImportParser.poolName(actualPool), resourceId = resourceId, quality = quality, type = type, name = item.optString("name"), count = count, time = time, offRate = offRate)
                }
            } }.onFailure { failures += "${ImportParser.poolName(pool)}：${it.message ?: "请求失败"}" }
        }
        if (output.isEmpty() && failures.isNotEmpty()) error("所有卡池请求均失败，首个错误：${failures.first()}")
        if (output.isEmpty()) error("没有获取到抽卡记录")
        FetchResult(params.uid, output, failures)
    }
}
