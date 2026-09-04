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

    suspend fun applySnapshot(source: File): Int = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { remote ->
            remote.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "云端数据库完整性校验失败" }
            }
            remote.rawQuery("SELECT schema_version FROM gacha_data_meta LIMIT 1", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == 1) { "云端数据库版本不受支持" }
            }
            val expected = mapOf(
                "gacha_records" to "id,player_id,card_pool_type,card_pool_name,resource_id,quality_level,resource_type,name,count,time,is_off_rate,occurrence_no,order_in_timestamp,is_mock,mock_batch_id",
                "player_import_info" to "player_id,last_imported_at,is_inferred",
                "pool_history_boundaries" to "player_id,card_pool_type,earliest_time,earliest_time_count,confirmed_at",
            )
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
        val db = requireNotNull(database).openHelper.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS cloud", arrayOf(source.path))
        try {
            db.beginTransaction()
            try {
                listOf("gacha_records", "player_import_info", "pool_history_boundaries").forEach { table ->
                    db.execSQL("DELETE FROM $table")
                    db.execSQL("INSERT INTO $table SELECT * FROM cloud.$table")
                }
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
