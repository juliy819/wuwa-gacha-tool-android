package com.wuwa.gachatool

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import org.json.JSONObject

data class SyncApplyResult(val envelope: SyncEnvelope, val stats: MergeStats)

/** 覆盖本机前的预检结果：只用于告知用户会丢掉什么，不修改任何数据。 */
data class SyncApplyPreview(
    val lostCount: Int,
    val lostEarliest: String?,
    val lostLatest: String?,
    val localCount: Int,
    val remoteCount: Int,
)

/**
 * 覆盖前预检用的比对语句：按“记录身份”聚合后比较条数差，因此同一秒多条
 * 完全相同记录只少了一部分的情况也能算准；身份口径与 mergeRecords 一致
 * （不含 occurrenceNo，因为它会随来源顺序重新编号）。
 */
private const val SYNC_APPLY_PREVIEW_SQL = """
    WITH local_counts AS (
        SELECT player_id, card_pool_type, time, resource_id, quality_level, resource_type,
               count, is_mock, COALESCE(mock_batch_id, '') AS batch, COUNT(*) AS hits
        FROM main.gacha_records
        GROUP BY player_id, card_pool_type, time, resource_id, quality_level, resource_type,
                 count, is_mock, batch
    ),
    remote_counts AS (
        SELECT player_id, card_pool_type, time, resource_id, quality_level, resource_type,
               count, is_mock, COALESCE(mock_batch_id, '') AS batch, COUNT(*) AS hits
        FROM cloud.gacha_records
        GROUP BY player_id, card_pool_type, time, resource_id, quality_level, resource_type,
                 count, is_mock, batch
    )
    SELECT
        COALESCE(SUM(MAX(local_counts.hits - COALESCE(remote_counts.hits, 0), 0)), 0),
        MIN(CASE WHEN local_counts.hits > COALESCE(remote_counts.hits, 0) THEN local_counts.time END),
        MAX(CASE WHEN local_counts.hits > COALESCE(remote_counts.hits, 0) THEN local_counts.time END),
        (SELECT COUNT(*) FROM main.gacha_records),
        (SELECT COUNT(*) FROM cloud.gacha_records)
    FROM local_counts
    LEFT JOIN remote_counts
        ON local_counts.player_id = remote_counts.player_id
       AND local_counts.card_pool_type = remote_counts.card_pool_type
       AND local_counts.time = remote_counts.time
       AND local_counts.resource_id = remote_counts.resource_id
       AND local_counts.quality_level = remote_counts.quality_level
       AND local_counts.resource_type = remote_counts.resource_type
       AND local_counts.count = remote_counts.count
       AND local_counts.is_mock = remote_counts.is_mock
       AND local_counts.batch = remote_counts.batch
"""

/** 同步备份的保留份数，避免长期占用手机存储。 */
private const val SYNC_BACKUP_RETENTION = 3

class SyncRepository(private val context: Context?, private val database: GachaDatabase?, private val legacyDao: GachaDao? = null) {
    constructor(context: Context, database: GachaDatabase) : this(context, database, null)
    constructor(dao: GachaDao) : this(null, null, dao)
    private val dao get() = database?.dao() ?: requireNotNull(legacyDao)

    suspend fun localUids(): List<String> = dao.uids()
    suspend fun recordCount(): Int = dao.uids().sumOf { dao.count(it) }

    suspend fun createSnapshot(target: File): File = withContext(Dispatchers.IO) {
        val dbOwner = requireNotNull(database)
        requireNotNull(context)
        target.delete()
        val db = dbOwner.openHelper.writableDatabase
        db.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { it.moveToFirst() }
        db.execSQL("VACUUM INTO ?", arrayOf(target.path))
        require(target.isFile && target.length() > 0) { "创建数据库快照失败" }
        target
    }

    /**
     * 覆盖本机前的整库备份。失败必须中止覆盖，否则一次误覆盖就无法挽回。
     * 只保留最近 [SYNC_BACKUP_RETENTION] 份，避免长期占用手机空间。
     */
    suspend fun backupBeforeApply(): File? = withContext(Dispatchers.IO) {
        val dir = File(requireNotNull(context).filesDir, "sync-backups")
        dir.mkdirs()
        val target = File(dir, "gacha-data-before-sync-${System.currentTimeMillis()}.db")
        createSnapshot(target)
        dir.listFiles { file -> file.name.startsWith("gacha-data-before-sync-") }
            ?.sortedByDescending { it.name }
            ?.drop(SYNC_BACKUP_RETENTION)
            ?.forEach { it.delete() }
        target
    }

    /**
     * 校验云端快照的结构兼容性。覆盖与覆盖前预检共用同一套规则，
     * 保证预检通过就意味着覆盖可以真正执行；返回快照是否带元数据表。
     */
    private fun validateSnapshot(source: File): Boolean {
        var hasMetaTable = false
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { remote ->
            remote.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "云端数据库完整性校验失败" }
            }
            val expected = mapOf(
                "gacha_records" to "id,player_id,card_pool_type,card_pool_name,resource_id,quality_level,resource_type,name,count,time,is_off_rate,occurrence_no,order_in_timestamp,is_mock,mock_batch_id",
                "player_import_info" to "player_id,last_imported_at,is_inferred",
                "pool_history_boundaries" to "player_id,card_pool_type,earliest_time,earliest_time_count,confirmed_at",
            )
            hasMetaTable = remote.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf("gacha_data_meta"),
            ).use { it.moveToFirst() }
            val version = if (hasMetaTable) remote.rawQuery(
                "SELECT schema_version FROM gacha_data_meta LIMIT 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 } else 0
            // Older desktop/Android snapshots may have no metadata row yet. Their
            // table layout is still the v1 contract and the metadata is restored below.
            require(version == 0 || version == 1) { "云端数据库版本不受支持: $version" }
            expected.forEach { (table, columns) ->
                remote.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { c ->
                    require(c.moveToFirst()) { "云端数据库缺少必要数据表 $table" }
                }
                remote.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    val actual = buildList { while (c.moveToNext()) add(c.getString(1)) }
                    require(actual == columns.split(',')) { "云端数据表 $table 字段不兼容" }
                }
                remote.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    require(c.moveToFirst() && c.getLong(0) <= 2_000_000L) { "云端数据表 $table 超过安全行数限制" }
                }
            }
        }
        return hasMetaTable
    }

    /**
     * 覆盖本机前的预检：本机会丢掉哪些记录、丢的是哪个时间段。
     * 只读取与比对，不写入任何数据。
     */
    suspend fun previewApply(source: File): SyncApplyPreview = withContext(Dispatchers.IO) {
        validateSnapshot(source)
        val db = requireNotNull(database).openHelper.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS cloud", arrayOf(source.path))
        try {
            // Room 的 SupportSQLiteDatabase 走 query(SimpleSQLiteQuery)，
            // 与 createSnapshot 一样，不能直接调框架的 rawQuery。
            db.query(SimpleSQLiteQuery(SYNC_APPLY_PREVIEW_SQL)).use { cursor ->
                // 聚合查询一定返回一行；取不到时按“没有丢”处理，不阻断同步。
                if (cursor.moveToFirst()) {
                    SyncApplyPreview(
                        lostCount = cursor.getInt(0).coerceAtLeast(0),
                        lostEarliest = cursor.getString(1),
                        lostLatest = cursor.getString(2),
                        localCount = cursor.getInt(3),
                        remoteCount = cursor.getInt(4),
                    )
                } else {
                    SyncApplyPreview(0, null, null, 0, 0)
                }
            }
        } finally {
            db.execSQL("DETACH DATABASE cloud")
        }
    }

    suspend fun applySnapshot(source: File): Int = withContext(Dispatchers.IO) {
        val hasMetaTable = validateSnapshot(source)
        val db = requireNotNull(database).openHelper.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS cloud", arrayOf(source.path))
        try {
            db.beginTransaction()
            try {
                listOf("gacha_records", "player_import_info", "pool_history_boundaries").forEach { table ->
                    db.execSQL("DELETE FROM $table")
                    db.execSQL("INSERT INTO $table SELECT * FROM cloud.$table")
                }
                db.execSQL("DELETE FROM gacha_data_meta")
                if (hasMetaTable) {
                    db.execSQL("INSERT OR IGNORE INTO gacha_data_meta(schema_version) SELECT schema_version FROM cloud.gacha_data_meta")
                }
                db.execSQL("INSERT OR IGNORE INTO gacha_data_meta(schema_version) VALUES(1)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { db.execSQL("DETACH DATABASE cloud") }
        requireNotNull(database).invalidationTracker.refreshVersionsAsync()
        recordCount()
    }

    suspend fun prepare(uid: String, updatedAt: String): SyncEnvelope =
        SyncEnvelope.fromGachaRecords(uid, dao.records(uid).first(), updatedAt)

    suspend fun applyCloud(uid: String, cloudPayload: String, updatedAt: String): SyncApplyResult {
        val cloud = SyncEnvelope.fromJson(JSONObject(cloudPayload))
        require(cloud.uid == uid) { "所选 UID 与云端同步数据不一致" }
        val merged = prepare(uid, updatedAt).mergeWithCloud(cloud, updatedAt)
        return SyncApplyResult(merged, dao.mergeSyncRecords(merged.toGachaRecords()))
    }
}