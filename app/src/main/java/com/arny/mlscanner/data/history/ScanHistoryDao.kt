package com.arny.mlscanner.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanHistoryDao {

    @Transaction
    @Query(
        """
        SELECT * FROM scans
        ORDER BY createdAt DESC
        """
    )
    fun observeAll(): Flow<List<ScanWithCodes>>

    @Transaction
    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun get(id: Long): ScanWithCodes?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCodes(codes: List<ScanCodeEntity>)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun deleteScan(id: Long)

    @Query("DELETE FROM scans")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scans")
    suspend fun count(): Int
}
