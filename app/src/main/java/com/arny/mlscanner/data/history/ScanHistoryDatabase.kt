package com.arny.mlscanner.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScanEntity::class, ScanCodeEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ScanHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): ScanHistoryDao

    companion object {
        @Volatile
        private var instance: ScanHistoryDatabase? = null

        fun get(context: Context): ScanHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScanHistoryDatabase::class.java,
                    "mlscanner_history.db"
                ).build().also { instance = it }
            }
    }
}
