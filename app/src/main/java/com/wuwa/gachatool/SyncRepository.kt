package com.wuwa.gachatool

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SyncRepository(private val context: Context, private val database: GachaDatabase) {
    private val dao get() = database.dao()

    suspend fun localUids(): List<String> = dao.uids()
    suspend fun recordCount(): Int = dao.uids().sumOf { dao.count(it) }

    suspend fun createSnapshot(target: File): File = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")).use { it.moveToFirst() }
        target.delete()
        context.getDatabasePath("gacha-data.db").copyTo(target, overwrite = true)
    }

    suspend fun applySnapshot(source: File): Int = withContext(Dispatchers.IO) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { remote ->
            remote.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "云端数据库完整性校验失败" }
            }
            remote.rawQuery("SELECT schema_version FROM gacha_data_meta LIMIT 1", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == 1) { "云端数据库版本不受支持" }
            }
        }
        val db = database.openHelper.writableDatabase
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
        recordCount()
    }
}
