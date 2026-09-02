package com.wuwa.gachatool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncProtocolTest {
    @Test fun roundTripKeepsIdentityFields() {
        val source = SyncRecord("106485288", "1", "2026-01-01 00:00:01", 2001, 5, "role", 1, 0, 0, "角色活动唤取", "限定角色", false)
        val restored = SyncEnvelope.fromJson(SyncEnvelope("106485288", listOf(source), "2026-09-02T00:00:00Z").toJson()).records.single()
        assertEquals(source.uid, restored.uid)
        assertEquals(source.resourceId, restored.resourceId)
        assertEquals(source.occurrenceNo, restored.occurrenceNo)
        assertEquals(source.orderInTimestamp, restored.orderInTimestamp)
    }

    @Test fun desktopJsonNullDoesNotBecomeMockBatchId() {
        val record = JSONObject()
            .put("pool", "1").put("time", "2026-01-01 00:00:01")
            .put("resource_id", 2001).put("quality", 5).put("resource_type", "role")
            .put("count", 1).put("occurrence_no", 0).put("order_in_timestamp", 0)
            .put("pool_name", "角色活动唤取").put("name", "限定角色")
            .put("off_rate", false).put("is_mock", false).put("mock_batch_id", JSONObject.NULL)
        val envelope = JSONObject()
            .put("schema_version", 1).put("uid", "106485288").put("updated_at", "2026-09-02T00:00:00Z")
            .put("records", org.json.JSONArray().put(record))
        assertEquals(null, SyncEnvelope.fromJson(envelope).records.single().mockBatchId)
    }

    @Test fun rejectsUnknownVersionAndBlankRequiredFields() {
        assertThrows(IllegalArgumentException::class.java) { SyncEnvelope.fromJson(JSONObject().put("schema_version", 2).put("uid", "106485288").put("records", "[]")) }
        assertThrows(IllegalArgumentException::class.java) { SyncEnvelope.fromJson(JSONObject().put("schema_version", 1).put("uid", "").put("records", "[]")) }
    }

    @Test fun rejectsDuplicateOccurrenceAndConflictingTimestampOrder() {
        val first = SyncRecord("106485288", "1", "2026-01-01 00:00:01", 2001, 5, "role", 1, 0, 0, "角色活动唤取", "限定角色", false)
        assertThrows(IllegalArgumentException::class.java) { SyncEnvelope("106485288", listOf(first, first), "2026-09-02T00:00:00Z").validate() }
        assertThrows(IllegalArgumentException::class.java) { SyncEnvelope("106485288", listOf(first, first.copy(resourceId = 2002)), "2026-09-02T00:00:00Z").validate() }
    }

    @Test fun cloudMergeKeepsMaximumDuplicateMultiplicityAndConverges() {
        val duplicate = SyncRecord("106485288", "1", "2026-01-01 00:00:01", 2001, 5, "role", 1, 0, 0, "角色活动唤取", "限定角色", false)
        val local = SyncEnvelope("106485288", listOf(duplicate, duplicate.copy(occurrenceNo = 1, orderInTimestamp = 1), duplicate.copy(resourceId = 2002, occurrenceNo = 0, orderInTimestamp = 2)), "2026-09-02T00:00:00Z")
        val cloud = SyncEnvelope("106485288", listOf(duplicate), "2026-09-02T00:00:01Z")
        val merged = local.mergeWithCloud(cloud, "2026-09-02T00:00:02Z")
        assertEquals(3, merged.records.size)
        assertEquals(listOf(0, 1, 2), merged.records.map { it.orderInTimestamp })
        assertEquals(merged.records.map { it.recordKey() }, merged.mergeWithCloud(merged, "2026-09-02T00:00:03Z").records.map { it.recordKey() })
    }

    @Test fun cloudMergeRejectsDifferentUid() {
        assertThrows(IllegalArgumentException::class.java) { SyncEnvelope("106485288", emptyList(), "2026-09-02T00:00:00Z").mergeWithCloud(SyncEnvelope("106485289", emptyList(), "2026-09-02T00:00:01Z"), "2026-09-02T00:00:02Z") }
    }

    @Test fun databaseSpecificOccurrenceNumbersAreCanonicalizedForSync() {
        val official = GachaRecord(uid = "106485288", pool = "1", poolName = "角色活动唤取", resourceId = 2001, quality = 3, type = "weapon", name = "武器", count = 1, time = "2026-01-01 00:00:01", offRate = false, occurrenceNo = 1, orderInTimestamp = 1)
        val mock = official.copy(isMock = true, mockBatchId = "batch", occurrenceNo = 0, orderInTimestamp = 0)
        val payload = SyncEnvelope.fromGachaRecords(official.uid, listOf(mock, official), "2026-09-02T00:00:00Z")
        assertEquals(listOf(0, 0), payload.records.map { it.occurrenceNo })
        assertEquals(listOf(0, 1), payload.records.map { it.orderInTimestamp })
    }
}
