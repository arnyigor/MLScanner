package com.arny.mlscanner.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна запись истории сканирования.
 *
 * Текст хранится как есть; изображение — отдельным файлом во внутренней папке приложения,
 * в базу пишется только имя файла. Личные данные не выходят за пределы телефона: база
 * живёт в filesDir, а папка изображений исключена из резервного копирования Android.
 */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "camera" | "file" | "editor" — источник кадра. */
    val source: String,
    /** Время сканирования, epoch millis. */
    val createdAt: Long,
    /** Имя файла обработанного кадра во внутренней папке; null — кадра нет. */
    val imageFile: String?,
    /** Имя файла оригинального кадра; null — оригинал не сохранялся. */
    val originalImageFile: String?,
    /** Ширина и высота кадра, отданного в OCR. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** Уверенность распознавания, 0..1; null — текст не распознавался. */
    val confidence: Float?,
    /** Язык распознавания. */
    val language: String,
    /** Полный распознанный текст. */
    val text: String,
    /** Сколько кодов найдено; сами коды — в ScanCodeEntity. */
    val codeCount: Int
)
