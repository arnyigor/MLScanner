package com.arny.mlscanner.data.history

import androidx.room.Embedded
import androidx.room.Relation

/** Скан с его кодами; Room собирает это из двух таблиц. */
data class ScanWithCodes(
    @Embedded val scan: ScanEntity,
    @Relation(parentColumn = "id", entityColumn = "scanId")
    val codes: List<ScanCodeEntity>
)
