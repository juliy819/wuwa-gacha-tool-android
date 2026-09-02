package com.wuwa.gachatool

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "gacha_records", indices = [
    Index(name = "uq_gacha_record_occurrence", value = ["player_id", "card_pool_type", "time", "resource_id", "quality_level", "resource_type", "count", "occurrence_no"], unique = true),
    Index(name = "idx_gacha_records_player_time", value = ["player_id", "time", "order_in_timestamp", "id"]),
    Index(name = "idx_gacha_records_time", value = ["time", "order_in_timestamp", "id"]),
])
data class GachaRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, @ColumnInfo(name="player_id") val uid: String, @ColumnInfo(name="card_pool_type") val pool: String, @ColumnInfo(name="card_pool_name") val poolName: String, @ColumnInfo(name="resource_id") val resourceId: Long, @ColumnInfo(name="quality_level") val quality: Int, @ColumnInfo(name="resource_type") val type: String, val name: String, val count: Int, val time: String, @ColumnInfo(name="is_off_rate", defaultValue="0") val offRate: Boolean, @ColumnInfo(name="occurrence_no", defaultValue="0") val occurrenceNo: Int = 0, @ColumnInfo(name="order_in_timestamp", defaultValue="0") val orderInTimestamp: Int = 0, @ColumnInfo(name="is_mock", defaultValue="0") val isMock: Boolean = false, @ColumnInfo(name="mock_batch_id") val mockBatchId: String? = null)

@Entity(tableName="player_import_info") data class PlayerImportInfo(@PrimaryKey @ColumnInfo(name="player_id") val playerId:String, @ColumnInfo(name="last_imported_at") val lastImportedAt:String?, @ColumnInfo(name="is_inferred", defaultValue="0") val isInferred:Boolean=false)
@Entity(tableName="pool_history_boundaries", primaryKeys=["player_id","card_pool_type"]) data class PoolHistoryBoundary(@ColumnInfo(name="player_id") val playerId:String, @ColumnInfo(name="card_pool_type") val poolType:String, @ColumnInfo(name="earliest_time") val earliestTime:String, @ColumnInfo(name="earliest_time_count") val earliestTimeCount:Int, @ColumnInfo(name="confirmed_at") val confirmedAt:String)
@Entity(tableName="gacha_data_meta") data class GachaDataMeta(@PrimaryKey @ColumnInfo(name="schema_version") val schemaVersion:Int=1)

data class MergeStats(val importedCount: Int, val addedCount: Int, val duplicateCount: Int, val totalCount: Int)
data class UidRecordCount(val uid: String, val recordCount: Int)

@Dao interface GachaDao {
    @Query("SELECT * FROM gacha_records WHERE player_id = :uid ORDER BY time DESC, order_in_timestamp ASC, id ASC") fun records(uid: String): Flow<List<GachaRecord>>
    @Query("SELECT * FROM gacha_records WHERE player_id = :uid AND card_pool_type IN (:pools) ORDER BY time DESC, id DESC") fun recordsForPools(uid: String, pools: List<String>): Flow<List<GachaRecord>>
    @Query("SELECT DISTINCT player_id FROM gacha_records ORDER BY player_id") fun observeUids(): Flow<List<String>>
    @Query("SELECT player_id AS uid, COUNT(*) AS recordCount FROM gacha_records GROUP BY player_id ORDER BY player_id") fun observeUidRecordCounts(): Flow<List<UidRecordCount>>
    @Query("SELECT DISTINCT player_id FROM gacha_records ORDER BY player_id") suspend fun uids(): List<String>
    @Query("SELECT id FROM gacha_records WHERE player_id=:uid AND card_pool_type=:pool AND time=:time AND resource_id=:resourceId AND quality_level=:quality AND resource_type=:type AND count=:count AND is_mock=:isMock AND (:isMock=0 OR mock_batch_id=:mockBatchId OR (mock_batch_id IS NULL AND :mockBatchId IS NULL)) ORDER BY occurrence_no ASC")
    suspend fun matchingIds(uid: String, pool: String, time: String, resourceId: Long, quality: Int, type: String, count: Int, isMock: Boolean, mockBatchId: String?): List<Long>
    @Query("SELECT COALESCE(MAX(occurrence_no), -1) + 1 FROM gacha_records WHERE player_id=:uid AND card_pool_type=:pool AND time=:time AND resource_id=:resourceId AND quality_level=:quality AND resource_type=:type AND count=:count")
    suspend fun nextOccurrence(uid: String, pool: String, time: String, resourceId: Long, quality: Int, type: String, count: Int): Int
    @Query("UPDATE gacha_records SET card_pool_name=:poolName, name=:name, is_off_rate=:offRate WHERE id=:id")
    suspend fun updateDisplay(id: Long, poolName: String, name: String, offRate: Boolean)
    @Query("UPDATE gacha_records SET card_pool_name=:poolName, name=:name, is_off_rate=:offRate, order_in_timestamp=:orderInTimestamp WHERE id=:id")
    suspend fun updateSyncedDisplay(id: Long, poolName: String, name: String, offRate: Boolean, orderInTimestamp: Int)
    @Insert suspend fun insert(record: GachaRecord): Long
    @Query("SELECT COUNT(*) FROM gacha_records WHERE player_id=:uid") suspend fun count(uid: String): Int
    @Query("DELETE FROM gacha_records") suspend fun clear()

    @Transaction
    suspend fun mergeRecords(records: List<GachaRecord>, applySourceOrder: Boolean = false): MergeStats {
        if (records.isEmpty()) return MergeStats(0, 0, 0, 0)
        val uid = records.first().uid
        require(records.all { it.uid == uid }) { "一次导入中包含多个 UID，无法安全合并" }
        val occurrenceCounts = mutableMapOf<String, Int>()
        val timestampOrders = mutableMapOf<String, Int>()
        var added = 0
        records.forEach { source ->
            val identity = listOf(source.uid, source.pool, source.time, source.resourceId, source.quality, source.type, source.count, source.isMock, source.mockBatchId).joinToString("\u0000")
            val occurrence = occurrenceCounts.getOrDefault(identity, 0).also { occurrenceCounts[identity] = it + 1 }
            val orderKey = listOf(source.uid, source.pool, source.time).joinToString("\u0000")
            val order = timestampOrders.getOrDefault(orderKey, 0).also { timestampOrders[orderKey] = it + 1 }
            val existing = matchingIds(source.uid, source.pool, source.time, source.resourceId, source.quality, source.type, source.count, source.isMock, source.mockBatchId).getOrNull(occurrence)
            if (existing != null) {
                if (applySourceOrder) updateSyncedDisplay(existing, source.poolName, source.name, source.offRate, order)
                else updateDisplay(existing, source.poolName, source.name, source.offRate)
            } else {
                val next = nextOccurrence(source.uid, source.pool, source.time, source.resourceId, source.quality, source.type, source.count)
                insert(source.copy(id = 0, occurrenceNo = next, orderInTimestamp = order))
                added += 1
            }
        }
        return MergeStats(records.size, added, records.size - added, count(uid))
    }

    @Transaction
    suspend fun mergeSyncRecords(records: List<GachaRecord>): MergeStats = mergeRecords(records, true)
}

data class PoolSummary(val pulls: Int, val fiveStars: Int, val offRate: Int, val pity: Int) {
    val average: Double get() = if (fiveStars == 0) 0.0 else pulls.toDouble() / fiveStars
    val upAverage: Double get() = if (fiveStars == 0) 0.0 else pulls.toDouble() / (fiveStars - offRate).coerceAtLeast(1)
}

@Database(entities = [GachaRecord::class, PlayerImportInfo::class, PoolHistoryBoundary::class, GachaDataMeta::class], version = 4, exportSchema = false)
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
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gacha_records ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE gacha_records ADD COLUMN mockBatchId TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE gacha_records RENAME TO gacha_records_android_old")
            db.execSQL("CREATE TABLE gacha_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, player_id TEXT NOT NULL, card_pool_type TEXT NOT NULL, card_pool_name TEXT NOT NULL, resource_id INTEGER NOT NULL, quality_level INTEGER NOT NULL, resource_type TEXT NOT NULL, name TEXT NOT NULL, count INTEGER NOT NULL, time TEXT NOT NULL, is_off_rate INTEGER NOT NULL DEFAULT 0, occurrence_no INTEGER NOT NULL DEFAULT 0, order_in_timestamp INTEGER NOT NULL DEFAULT 0, is_mock INTEGER NOT NULL DEFAULT 0, mock_batch_id TEXT)")
            db.execSQL("INSERT INTO gacha_records SELECT id,uid,pool,poolName,resourceId,quality,type,name,count,time,offRate,occurrenceNo,orderInTimestamp,isMock,mockBatchId FROM gacha_records_android_old")
            db.execSQL("DROP TABLE gacha_records_android_old")
            db.execSQL("CREATE UNIQUE INDEX uq_gacha_record_occurrence ON gacha_records(player_id,card_pool_type,time,resource_id,quality_level,resource_type,count,occurrence_no)")
            db.execSQL("CREATE INDEX idx_gacha_records_player_time ON gacha_records(player_id,time,order_in_timestamp,id)")
            db.execSQL("CREATE INDEX idx_gacha_records_time ON gacha_records(time,order_in_timestamp,id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS player_import_info (player_id TEXT NOT NULL PRIMARY KEY, last_imported_at TEXT, is_inferred INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pool_history_boundaries (player_id TEXT NOT NULL, card_pool_type TEXT NOT NULL, earliest_time TEXT NOT NULL, earliest_time_count INTEGER NOT NULL, confirmed_at TEXT NOT NULL, PRIMARY KEY(player_id,card_pool_type))")
            db.execSQL("CREATE TABLE IF NOT EXISTS gacha_data_meta (schema_version INTEGER NOT NULL PRIMARY KEY)")
            db.execSQL("INSERT OR IGNORE INTO gacha_data_meta(schema_version) VALUES(1)")
        } }
        fun create(context: Context): GachaDatabase {
            val old = context.getDatabasePath("gacha.db"); val current = context.getDatabasePath("gacha-data.db")
            if (!current.exists() && old.exists()) old.copyTo(current)
            return Room.databaseBuilder(context, GachaDatabase::class.java, "gacha-data.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        }
    }
}
