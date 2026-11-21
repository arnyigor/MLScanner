package com.arny.mlscanner.data.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Найденный QR- или штрих-код, привязанный к скану. */
@Entity(
    tableName = "scan_codes",
    foreignKeys = [
        ForeignKey(
            entity = ScanEntity::class,
            parentColumns = ["id"],
            childColumns = ["scanId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("scanId")]
)
data class ScanCodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    /** Тип содержимого: URL, PHONE, EMAIL, TEXT… */
    val contentType: String,
    /** Формат кода: QR_CODE, EAN_13… */
    val format: String,
    val rawValue: String
)
