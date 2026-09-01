package com.wuwa.gachatool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GachaMergeTest {
    private lateinit var database: GachaDatabase
    private lateinit var dao: GachaDao

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GachaDatabase::class.java).build()
        dao = database.dao()
    }

    @After fun tearDown() = database.close()

    private fun record(uid: String = "106485288", pool: String = "1", resourceId: Long = 21010013, quality: Int = 3, time: String = "2026-07-10 13:17:11", name: String = "暗夜长刃") =
        GachaRecord(uid = uid, pool = pool, poolName = ImportParser.poolName(pool), resourceId = resourceId, quality = quality, type = if (resourceId < 100000) "role" else "weapon", name = name, count = 1, time = time, offRate = false)

    @Test fun preservesIdenticalSameSecondPullsAndRepeatImportIsIdempotent() = runBlocking {
        val duplicate = record()
        val snapshot = listOf(duplicate, duplicate, record(resourceId = 1602, quality = 4, name = "丹瑾"))
        val first = dao.mergeRecords(snapshot)
        val second = dao.mergeRecords(snapshot)
        assertEquals(3, first.addedCount)
        assertEquals(0, second.addedCount)
        assertEquals(3, second.duplicateCount)
        val stored = dao.records("106485288").first()
        assertEquals(listOf(0, 1), stored.filter { it.resourceId == duplicate.resourceId }.map { it.occurrenceNo }.sorted())
        assertEquals(listOf(0, 1, 2), stored.map { it.orderInTimestamp })
    }

    @Test fun laterCompleteSnapshotRestoresPreviouslyMissingDuplicate() = runBlocking {
        val duplicate = record()
        dao.mergeRecords(listOf(duplicate))
        val result = dao.mergeRecords(listOf(duplicate, duplicate))
        assertEquals(1, result.addedCount)
        assertEquals(2, result.totalCount)
    }

    @Test fun isolatesIdentityByUidAndOfficialPool() = runBlocking {
        val base = record()
        val first = dao.mergeRecords(listOf(base, base.copy(pool = "12", poolName = ImportParser.poolName("12"))))
        val second = dao.mergeRecords(listOf(base.copy(uid = "106485289")))
        assertEquals(2, first.addedCount)
        assertEquals(1, second.addedCount)
        assertEquals(1, dao.records("106485288").first().count { it.pool == "1" })
        assertEquals(1, dao.records("106485288").first().count { it.pool == "12" })
        assertEquals(1, dao.records("106485289").first().size)
    }

    @Test fun refreshesDerivedDisplayFieldsWithoutDuplicatingRecord() = runBlocking {
        val source = record(resourceId = 1104, quality = 5, name = "旧名称")
        dao.mergeRecords(listOf(source))
        val result = dao.mergeRecords(listOf(source.copy(name = "凌阳", offRate = true)))
        val stored = dao.records(source.uid).first().single()
        assertEquals(0, result.addedCount)
        assertEquals("凌阳", stored.name)
        assertEquals(true, stored.offRate)
    }

    @Test fun keepsQualityTypeAndCountAsIdentityFields() = runBlocking {
        val source = record()
        val result = dao.mergeRecords(
            listOf(
                source,
                source.copy(quality = 4),
                source.copy(type = "role"),
                source.copy(count = 10),
            ),
        )

        assertEquals(4, result.addedCount)
        assertEquals(4, dao.records(source.uid).first().size)
    }
}
