package com.wuwa.gachatool

import java.net.URI

data class PullParams(val uid: String, val recordId: String, val resourcesId: String, val serverId: String, val lang: String)
object ImportParser {
    fun params(raw: String): PullParams {
        val uri = URI(raw); val map = linkedMapOf<String, String>()
        fun read(q: String?) { q?.split('&')?.forEach { val p = it.split('=', limit = 2); if (p.size == 2) map[p[0]] = java.net.URLDecoder.decode(p[1], "UTF-8") } }
        read(uri.rawQuery); read(uri.rawFragment?.substringAfter('?', ""))
        val uid = map["player_id"].orEmpty()
        val recordId = map["record_id"].orEmpty()
        require(uid.isNotEmpty() && recordId.isNotEmpty()) { "URL 参数不完整" }
        return PullParams(uid, recordId, map["resources_id"].orEmpty(), map["svr_id"].orEmpty(), map["lang"].orEmpty())
    }
    private val apiPoolIds = mapOf(
        "角色精准调谐" to "1", "武器精准调谐" to "2", "角色常驻调谐" to "3", "武器常驻调谐" to "4",
        "新手调谐" to "5", "新手自选调谐" to "6", "新手自选调谐（感恩定向调谐）" to "7",
        "角色新旅调谐" to "8", "武器新旅调谐" to "9", "角色联动调谐" to "10", "武器联动调谐" to "11",
        "角色忆旅调谐" to "12", "武器忆旅调谐" to "13",
    )
    fun poolIdFromApiName(name: String): String? = apiPoolIds[name]
    fun poolName(pool: String) = when (pool) {
        "1" -> "角色活动唤取"
        "2" -> "武器活动唤取"
        "3" -> "角色常驻唤取"
        "4" -> "武器常驻唤取"
        "5" -> "新手唤取"
        "6", "7" -> "新手自选唤取"
        "8" -> "角色新旅唤取"
        "9" -> "武器新旅唤取"
        "10" -> "角色联动唤取"
        "11" -> "武器联动唤取"
        "12" -> "角色忆旅唤取"
        "13" -> "武器忆旅唤取"
        else -> "未知卡池"
    }
}
