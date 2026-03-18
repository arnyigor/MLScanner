// ────────────────────────────────────────────────────────────────
//  MatchingEngine.kt – полностью отрефакторенный вариант
// ────────────────────────────────────────────────────────────────

package com.arny.mlscanner.data.matching

import android.util.Log
import com.arny.mlscanner.data.db.ProductDao
import com.arny.mlscanner.data.db.ProductEntity
import com.arny.mlscanner.domain.models.*
import com.opencsv.CSVReader
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.json.JSONArray
import java.io.File
import java.io.FileReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Движок сопоставления данных.
 *
 * **Ключевая идея** – перед сравнением SKU «чистим» обе строки от всех символов,
 * кроме букв и цифр. Это устраняет различия, вызванные дефисами, пробелами,
 * подчёркиваниями и т.п., которые часто появляются в OCR‑тексте.
 */
class MatchingEngine(
    private val productDao: ProductDao
) {

    companion object {
        private const val TAG = "MatchingEngine"
        /** Минимальный score для того, чтобы считать совпадение «нормальным» */
        private const val DEFAULT_FUZZY_THRESHOLD = 90
        /**
         * Регулярка, отбрасывающая всё, что не является буквой/цифрой.
         * Мы оставляем только латиницу – если понадобится кириллица,
         * её можно добавить в паттерн.
         */
        private val CLEANER_REGEX = Regex("[^A-Za-z0-9]")
    }

    /** Лёгкая обёртка над FuzzyWuzzy, чтобы не зависеть от конкретной реализации */
    private val fuzzyMatcher = FuzzyMatcher()

    /* ────────────────────── IMPORT DATA ────────────────────── */

    suspend fun importFromCsv(csvFile: File, mapping: FieldMapping): Boolean =
        try {
            CSVReader(FileReader(csvFile)).use { reader ->
                val records = reader.readAll()
                val items = records.drop(1).mapNotNull { record ->
                    try {
                        if (record.size > maxOf(mapping.skuIndex, mapping.nameIndex)) {
                            ProductItem(
                                sku = record[mapping.skuIndex].trim(),
                                name = record[mapping.nameIndex].trim(),
                                price = if (mapping.priceIndex != null && record.size > mapping.priceIndex)
                                    record[mapping.priceIndex].toDoubleOrNull() else null,
                                category = if (mapping.categoryIndex != null && record.size > mapping.categoryIndex)
                                    record[mapping.categoryIndex].trim() else null
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping invalid record: ${record.joinToString(",")}", e)
                        null
                    }
                }

                productDao.deleteAllProducts()
                val entities = items.map { it.copy(sku = it.sku.uppercase()) }
                    .map(ProductEntity::fromProductItem)
                productDao.insertProducts(entities)

                // Принудительная переиндексация FTS
                productDao.rebuildFtsIndex()

                Log.d(TAG, "Imported ${items.size} items from CSV")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importing from CSV", e)
            false
        }

    suspend fun importFromJson(jsonFile: File, mapping: FieldMapping): Boolean =
        try {
            val jsonString = jsonFile.readText()
            val jsonArray = JSONArray(jsonString)

            val items = (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val obj = jsonArray.getJSONObject(i)
                    ProductItem(
                        sku = obj.getString(mapping.skuFieldName),
                        name = obj.getString(mapping.nameFieldName),
                        price = if (obj.has(mapping.priceFieldName))
                            obj.getDouble(mapping.priceFieldName) else null,
                        category = if (obj.has(mapping.categoryFieldName))
                            obj.getString(mapping.categoryFieldName) else null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid JSON object", e)
                    null
                }
            }

            productDao.deleteAllProducts()
            val entities = items.map { it.copy(sku = it.sku.uppercase()) }
                .map(ProductEntity::fromProductItem)
            productDao.insertProducts(entities)

            // Принудительная переиндексация FTS
            productDao.rebuildFtsIndex()

            Log.d(TAG, "Imported ${items.size} items from JSON")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing from JSON", e)
            false
        }

    /* ────────────────────── MATCHING LOGIC ────────────────────── */

    /**
     * Нормализация SKU – удаляем все символы,
     * кроме букв и цифр, и переводим в верхний регистр.
     *
     * Это делаем **только** перед сравнением. Для FTS‑запросов
     * используем исходную строку (с разделителями), чтобы индекс
     * корректно сработал.
     */
    private fun normalizeSku(input: String): String =
        input.replace(CLEANER_REGEX, "").uppercase()

    suspend fun match(
        text: String,
        mode: MatchMode = MatchMode.AUTO,
        threshold: Int = DEFAULT_FUZZY_THRESHOLD
    ): List<MatchedItem> {
        val normalizedForDao = normalizeSku(text)          // для DAO‑запросов

        /* ---------- 1️⃣ Точный поиск ---------- */
        productDao.getProductBySku(normalizedForDao)?.let { entity ->
            return listOf(
                MatchedItem(
                    originalText = text,
                    matchedItem = entity.toProductItem(),
                    confidence = 100f,
                    boundingBox = BoundingBox(0f, 0f, 0f, 0f)
                )
            )
        }

        /* ---------- 2️⃣ FTS поиск (быстрый набор кандидатов) ---------- */
        val ftsResults = withContext(Dispatchers.IO) {
            productDao.searchProductsFts(text.trim().uppercase())
        }

        /* ---------- 3️⃣ Фазовый fuzzy‑поиск ---------- */
        if (mode == MatchMode.AUTO || mode == MatchMode.FUZZY) {
            val fuzzyThreshold = threshold.coerceAtLeast(0).coerceAtMost(100)

            // Сортируем только по качеству, не меняем порядок исходных данных
            val results = ftsResults
                .map { it.toProductItem() }
                .mapNotNull { item ->
                    val score = fuzzyMatcher.tokenSetRatio(
                        normalizeSku(text),
                        normalizeSku(item.sku)
                    )
                    if (score >= fuzzyThreshold) {
                        MatchedItem(
                            originalText = text,
                            matchedItem = item,
                            confidence = score.toFloat(),
                            boundingBox = BoundingBox(0f, 0f, 0f, 0f)
                        )
                    } else null
                }
                .sortedByDescending { it.confidence }
                .take(5)

            if (results.isNotEmpty()) return results

            /* ---------- 3.1 fallback: полный поиск по таблице ---------- */
            val fallbackList = withContext(Dispatchers.IO) {
                productDao.searchProducts(normalizedForDao)
            }

            val fallbackResults = fallbackList
                .map { it.toProductItem() }
                .mapNotNull { item ->
                    val score = fuzzyMatcher.tokenSetRatio(
                        normalizeSku(text),
                        normalizeSku(item.sku)
                    )
                    if (score >= fuzzyThreshold) {
                        MatchedItem(
                            originalText = text,
                            matchedItem = item,
                            confidence = score.toFloat(),
                            boundingBox = BoundingBox(0f, 0f, 0f, 0f)
                        )
                    } else null
                }
                .sortedByDescending { it.confidence }
                .take(5)

            if (fallbackResults.isNotEmpty()) return fallbackResults
        }

        /* ---------- 4️⃣ Нет совпадений ---------- */
        return emptyList()
    }

    /* ---------- Пакетный режим ---------- */
    suspend fun batchMatch(
        texts: List<String>,
        mode: MatchMode = MatchMode.AUTO,
        threshold: Int = DEFAULT_FUZZY_THRESHOLD
    ): MatchingResult {
        val matchedItems = mutableListOf<MatchedItem>()
        val unmatchedTexts = mutableListOf<String>()

        for (text in texts) {
            val res = match(text, mode, threshold)
            if (res.isNotEmpty()) matchedItems.addAll(res) else unmatchedTexts.add(text)
        }

        return MatchingResult(
            matchedItems = matchedItems,
            unmatchedTexts = unmatchedTexts,
            confidenceThreshold = threshold.toFloat(),
            timestamp = System.currentTimeMillis()
        )
    }

    /* ---------- Счётчик записей ---------- */
    suspend fun getItemCount(): Int =
        withContext(Dispatchers.IO) { productDao.getProductCount() }
}

/* ────────────────────── ENUMS & HELPERS ────────────────────── */

enum class MatchMode {
    EXACT, FUZZY, AUTO
}

data class FieldMapping(
    val skuIndex: Int,
    val nameIndex: Int,
    val priceIndex: Int? = null,
    val categoryIndex: Int? = null,
    val skuFieldName: String = "sku",
    val nameFieldName: String = "name",
    val priceFieldName: String = "price",
    val categoryFieldName: String = "category"
)

class FuzzyMatcher {
    fun tokenSetRatio(a: String, b: String): Int =
        FuzzySearch.tokenSetRatio(a, b)
}
