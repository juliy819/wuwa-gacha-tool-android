package com.wuwa.gachatool

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

const val SYNC_SCHEMA_VERSION = 1

data class SyncEnvelope(val uid: String, val records: List<SyncRecord>, val updatedAt: String, val schemaVersion: Int = SYNC_SCHEMA_VERSION) {
    fun validate() {
        require(schemaVersion == SYNC_SCHEMA_VERSION) { "不支持的同步数据版本: $schemaVersion" }
        require(uid.isNotBlank() && uid.length <= 64 && uid.all(Char::isDigit)) { "同步数据缺少 UID" }
        try { Instant.parse(updatedAt) } catch (_: DateTimeParseException) { throw IllegalArgumentException("同步数据的更新时间格式无效") }
        require(records.size <= 200_000) { "同步数据记录数量超过限制" }
        require(records.all { it.pool.isNotBlank() && it.time.isNotBlank() && it.resourceType.isNotBlank() }) { "同步数据包含缺少卡池、时间或资源类型的记录" }
        require(records.all { runCatching { LocalDateTime.parse(it.time, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")) }.isSuccess }) { "同步数据包含无效的记录时间" }
        require(records.all { it.uid == uid }) { "同步数据包含多个 UID" }
        require(records.all { it.pool.toIntOrNull() in 1..13 && it.quality in 3..5 && it.resourceType in setOf("role", "weapon") && it.resourceId > 0 && it.name.length <= 256 && it.poolName.length <= 64 }) { "同步数据包含非法的卡池或资源字段" }
        require(records.all { it.isMock || it.mockBatchId == null }) { "官方记录不能携带模拟批次标识" }
        require(records.all { it.occurrenceNo >= 0 && it.orderInTimestamp >= 0 && it.count in 1..100 }) { "同步数据包含非法的记录编号或数量" }
        require(records.map { listOf(it.pool, it.time, it.resourceId, it.quality, it.resourceType, it.count, it.isMock, it.mockBatchId, it.occurrenceNo) }.distinct().size == records.size) { "同步数据包含重复的 occurrence 编号" }
        require(records.map { listOf(it.pool, it.time, it.orderInTimestamp) }.distinct().size == records.size) { "同步数据包含冲突的同秒顺序" }
        val occurrenceGroups = records.groupBy { listOf(it.pool, it.time, it.resourceId, it.quality, it.resourceType, it.count, it.isMock, it.mockBatchId) }.values
        val orderGroups = records.groupBy { listOf(it.pool, it.time) }.values
        require(occurrenceGroups.all { group -> group.map { it.occurrenceNo }.sorted() == group.indices.toList() }) { "同步数据的 occurrence 编号不连续" }
        require(orderGroups.all { group -> group.map { it.orderInTimestamp }.sorted() == group.indices.toList() }) { "同步数据的同秒顺序不连续" }
    }

    fun toJson(): JSONObject {
        validate()
        val result = JSONObject().put("schema_version", schemaVersion).put("uid", uid).put("updated_at", updatedAt)
        result.put("records", JSONArray(records.sortedWith(compareBy<SyncRecord> { it.time }.thenBy { it.pool }.thenBy { it.orderInTimestamp }.thenBy { it.occurrenceNo }.thenBy { it.resourceId }).map { it.toJson() }))
        return result
    }

    fun mergeWithCloud(cloud: SyncEnvelope, mergedAt: String): SyncEnvelope {
        validate(); cloud.validate()
        require(uid == cloud.uid) { "本地与云端 UID 不一致，拒绝合并" }
        val selected = linkedMapOf<String, SyncRecord>()
        records.forEach { selected[it.recordKey()] = it }
        cloud.records.forEach { selected[it.recordKey()] = it }
        val timestampOrder = sortedMapOf<String, MutableList<String>>()
        cloud.records.forEach { timestampOrder.getOrPut(it.timestampKey()) { mutableListOf() }.add(it.recordKey()) }
        records.forEach { record -> timestampOrder.getOrPut(record.timestampKey()) { mutableListOf() }.let { if (record.recordKey() !in it) it += record.recordKey() } }
        val merged = timestampOrder.values.flatMap { keys -> keys.mapIndexedNotNull { index, key -> selected.remove(key)?.copy(orderInTimestamp = index) } }
        return SyncEnvelope(uid, merged, mergedAt).also { it.validate() }
    }

    companion object {
        fun fromJson(json: JSONObject): SyncEnvelope {
            val uid = json.optString("uid")
            val values = buildList { val rows = json.optJSONArray("records") ?: JSONArray(); for (i in 0 until rows.length()) add(SyncRecord.fromJson(rows.getJSONObject(i), uid)) }
            return SyncEnvelope(uid, values, json.optString("updated_at"), json.optInt("schema_version", -1)).also { it.validate() }
        }
    }
}

data class SyncRecord(val uid: String, val pool: String, val time: String, val resourceId: Long, val quality: Int, val resourceType: String, val count: Int, val occurrenceNo: Int, val orderInTimestamp: Int, val poolName: String, val name: String, val offRate: Boolean, val isMock: Boolean = false, val mockBatchId: String? = null) {
    fun timestampKey() = listOf(pool, time).joinToString("\u0000")
    fun recordKey() = listOf(pool, time, resourceId, quality, resourceType, count, isMock, mockBatchId, occurrenceNo).joinToString("\u0000")
    fun toJson() = JSONObject().put("pool", pool).put("time", time).put("resource_id", resourceId).put("quality", quality).put("resource_type", resourceType).put("count", count).put("occurrence_no", occurrenceNo).put("order_in_timestamp", orderInTimestamp).put("pool_name", poolName).put("name", name).put("off_rate", offRate).put("is_mock", isMock).put("mock_batch_id", mockBatchId)
    companion object {
        fun fromJson(value: JSONObject, uid: String) = SyncRecord(uid, value.optString("pool"), value.optString("time"), value.optLong("resource_id", -1), value.optInt("quality", -1), value.optString("resource_type"), value.optInt("count", -1), value.optInt("occurrence_no", -1), value.optInt("order_in_timestamp", -1), value.optString("pool_name"), value.optString("name"), value.optBoolean("off_rate"), value.optBoolean("is_mock"), if (value.isNull("mock_batch_id")) null else value.optString("mock_batch_id").takeIf { it.isNotBlank() })
    }
}

fun SyncEnvelope.Companion.fromGachaRecords(uid: String, records: List<GachaRecord>, updatedAt: String): SyncEnvelope {
    require(uid.isNotBlank() && records.all { it.uid == uid }) { "同步数据必须只包含一个 UID" }
    val occurrences = mutableMapOf<List<Any?>, Int>()
    val orders = mutableMapOf<List<String>, Int>()
    val values = records.map {
        val identity = listOf(it.pool, it.time, it.resourceId, it.quality, it.type, it.count, it.isMock, it.mockBatchId)
        val timestamp = listOf(it.pool, it.time)
        SyncRecord(uid, it.pool, it.time, it.resourceId, it.quality, it.type, it.count, occurrences.getOrDefault(identity, 0).also { value -> occurrences[identity] = value + 1 }, orders.getOrDefault(timestamp, 0).also { value -> orders[timestamp] = value + 1 }, it.poolName, it.name, it.offRate, it.isMock, it.mockBatchId)
    }.sortedWith(compareBy<SyncRecord> { it.time }.thenBy { it.pool }.thenBy { it.orderInTimestamp }.thenBy { it.occurrenceNo }.thenBy { it.resourceId })
    return SyncEnvelope(uid, values, updatedAt).also { it.validate() }
}

fun SyncEnvelope.toGachaRecords(): List<GachaRecord> {
    validate()
    return records.sortedWith(compareBy<SyncRecord> { it.time }.thenBy { it.pool }.thenBy { it.orderInTimestamp }).map {
        GachaRecord(uid = uid, pool = it.pool, poolName = it.poolName.ifBlank { ImportParser.poolName(it.pool) }, resourceId = it.resourceId, quality = it.quality, type = it.resourceType, name = it.name, count = it.count, time = it.time, offRate = it.offRate, occurrenceNo = it.occurrenceNo, orderInTimestamp = it.orderInTimestamp, isMock = it.isMock, mockBatchId = it.mockBatchId)
    }
}
