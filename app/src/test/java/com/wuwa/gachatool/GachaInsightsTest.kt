package com.wuwa.gachatool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GachaInsightsTest {
    private fun record(id: Long, quality: Int, resourceId: Long, name: String, time: Int, offRate: Boolean = false) = GachaRecord(
        id = id, uid = "1", pool = "1", poolName = "角色活动唤取", resourceId = resourceId,
        quality = quality, type = "role", name = name, count = 1,
        time = "2026-01-01 00:00:${time.toString().padStart(2, '0')}", offRate = offRate,
    )

    @Test fun includesPrecedingOffRateInNextCharacterTotal() {
        val records = listOf(record(1, 5, 1104, "维里奈", 1), record(2, 3, 1, "三星", 2), record(3, 5, 2001, "赞妮", 3))
        val insight = characterAcquisitionInsights(records).single()
        assertEquals(1, insight.targetCount)
        assertEquals(1, insight.offRateCount)
        assertEquals(3, insight.totalPulls)
        assertEquals(listOf("维里奈", "赞妮"), insight.records.map { it.record.name })
        assertTrue(insight.records.first().isOffRate)
    }

    @Test fun ignoresTrailingOffRateAndSeparatesCharacters() {
        val records = listOf(record(1, 5, 2001, "赞妮", 1), record(2, 3, 1, "三星", 2), record(3, 5, 1104, "维里奈", 3))
        val insight = characterAcquisitionInsights(records).single()
        assertEquals("赞妮", insight.name)
        assertEquals(1, insight.totalPulls)
        assertEquals(1, insight.targetCount)
    }
}
