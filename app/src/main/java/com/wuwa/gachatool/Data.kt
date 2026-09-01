package com.wuwa.gachatool

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "gacha_records", indices = [Index(value = ["uid", "pool", "time", "resourceId", "quality", "type", "count", "occurrenceNo"], unique = true)])
data class GachaRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val uid: String, val pool: String, val poolName: String, val resourceId: Long, val quality: Int, val type: String, val name: String, val count: Int, val time: String, val offRate: Boolean, val occurrenceNo: Int = 0, val orderInTimestamp: Int = 0)

data class MergeStats(val importedCount: Int, val addedCount: Int, val duplicateCount: Int, val totalCount: Int)

@Dao interface GachaDao {
    @Query("SELECT * FROM gacha_records WHERE uid = :uid ORDER BY time DESC, orderInTimestamp ASC, id ASC") fun records(uid: String): Flow<List<GachaRecord>>
    @Query("SELECT * FROM gacha_records WHERE uid = :uid AND pool IN (:pools) ORDER BY time DESC, id DESC") fun recordsForPools(uid: String, pools: List<String>): Flow<List<GachaRecord>>
    @Query("SELECT DISTINCT uid FROM gacha_records ORDER BY uid") fun observeUids(): Flow<List<String>>
    @Query("SELECT DISTINCT uid FROM gacha_records ORDER BY uid") suspend fun uids(): List<String>
    @Query("SELECT id FROM gacha_records WHERE uid=:uid AND pool=:pool AND time=:time AND resourceId=:resourceId AND quality=:quality AND type=:type AND count=:count ORDER BY occurrenceNo ASC")
    suspend fun matchingIds(uid: String, pool: String, time: String, resourceId: Long, quality: Int, type: String, count: Int): List<Long>
    @Query("SELECT COALESCE(MAX(occurrenceNo), -1) + 1 FROM gacha_records WHERE uid=:uid AND pool=:pool AND time=:time AND resourceId=:resourceId AND quality=:quality AND type=:type AND count=:count")
    suspend fun nextOccurrence(uid: String, pool: String, time: String, resourceId: Long, quality: Int, type: String, count: Int): Int
    @Query("UPDATE gacha_records SET poolName=:poolName, name=:name, offRate=:offRate WHERE id=:id")
    suspend fun updateDisplay(id: Long, poolName: String, name: String, offRate: Boolean)
    @Insert suspend fun insert(record: GachaRecord): Long
    @Query("SELECT COUNT(*) FROM gacha_records WHERE uid=:uid") suspend fun count(uid: String): Int
    @Query("DELETE FROM gacha_records") suspend fun clear()

    @Transaction
    suspend fun mergeRecords(records: List<GachaRecord>): MergeStats {
        if (records.isEmpty()) return MergeStats(0, 0, 0, 0)
        val uid = records.first().uid
        require(records.all { it.uid == uid }) { "一次导入中包含多个 UID，无法安全合并" }
        val occurrenceCounts = mutableMapOf<String, Int>()
        val timestampOrders = mutableMapOf<String, Int>()
        var added = 0
        records.forEach { source ->
            val identity = listOf(source.uid, source.pool, source.time, source.resourceId, source.quality, source.type, source.count).joinToString("\u0000")
            val occurrence = occurrenceCounts.getOrDefault(identity, 0).also { occurrenceCounts[identity] = it + 1 }
            val orderKey = listOf(source.uid, source.pool, source.time).joinToString("\u0000")
            val order = timestampOrders.getOrDefault(orderKey, 0).also { timestampOrders[orderKey] = it + 1 }
            val existing = matchingIds(source.uid, source.pool, source.time, source.resourceId, source.quality, source.type, source.count).getOrNull(occurrence)
            if (existing != null) {
                updateDisplay(existing, source.poolName, source.name, source.offRate)
            } else {
                val next = nextOccurrence(source.uid, source.pool, source.time, source.resourceId, source.quality, source.type, source.count)
                insert(source.copy(id = 0, occurrenceNo = next, orderInTimestamp = order))
                added += 1
            }
        }
        return MergeStats(records.size, added, records.size - added, count(uid))
    }
}

data class PoolSummary(val pulls: Int, val fiveStars: Int, val offRate: Int, val pity: Int) {
    val average: Double get() = if (fiveStars == 0) 0.0 else pulls.toDouble() / fiveStars
    val upAverage: Double get() = if (fiveStars == 0) 0.0 else pulls.toDouble() / (fiveStars - offRate).coerceAtLeast(1)
}

@Database(entities = [GachaRecord::class], version = 2, exportSchema = false)
abstract class GachaDatabase : RoomDatabase() { abstract fun dao(): GachaDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gacha_records ADD COLUMN occurrenceNo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE gacha_records ADD COLUMN orderInTimestamp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX IF EXISTS index_gacha_records_uid_pool_time_resourceId")
                db.execSQL("UPDATE gacha_records SET occurrenceNo = (SELECT COUNT(*) - 1 FROM gacha_records AS prior WHERE prior.uid = gacha_records.uid AND prior.pool = gacha_records.pool AND prior.time = gacha_records.time AND prior.resourceId = gacha_records.resourceId AND prior.quality = gacha_records.quality AND prior.type = gacha_records.type AND prior.count = gacha_records.count AND prior.id <= gacha_records.id)")
                db.execSQL("UPDATE gacha_records SET orderInTimestamp = (SELECT COUNT(*) - 1 FROM gacha_records AS prior WHERE prior.uid = gacha_records.uid AND prior.pool = gacha_records.pool AND prior.time = gacha_records.time AND prior.id <= gacha_records.id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gacha_records_uid_pool_time_resourceId_quality_type_count_occurrenceNo ON gacha_records(uid, pool, time, resourceId, quality, type, count, occurrenceNo)")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, GachaDatabase::class.java, "gacha.db").addMigrations(MIGRATION_1_2).build()
    }
}
