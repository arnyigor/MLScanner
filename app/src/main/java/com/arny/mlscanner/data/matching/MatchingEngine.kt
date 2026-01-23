package com.arny.mlscanner.data.matching

import android.util.Log

import com.arny.mlscanner.data.db.ProductEntity
import com.arny.mlscanner.data.db.ProductDao
import com.arny.mlscanner.domain.models.MatchedItem
import com.arny.mlscanner.domain.models.MatchingResult
import com.arny.mlscanner.domain.models.ProductItem
import com.opencsv.CSVReader
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.json.JSONArray
import java.io.File
import java.io.FileReader


/**
 * Движок сопоставления данных
 * В соответствии с требованиями TECH.md:
 * - Режим "Exact Match" и "Fuzzy Match"
 * - Обработка опечаток
 * - Учет регистра и разделителей
 * - Результат с confidence score
 */
class MatchingEngine(private val productDao: ProductDao) {
    companion object {
        private const val TAG = "MatchingEngine"
        private const val DEFAULT_FUZZY_THRESHOLD = 90
    }

// DB and DAO are injected; no local database initialization needed
    private val fuzzyMatcher = FuzzyMatcher()

    /**
     * Импорт данных из CSV-файла
     */
    suspend fun importFromCsv(csvFile: File, mapping: FieldMapping): Boolean {
        return try {
            val reader = CSVReader(FileReader(csvFile))
            val records = reader.readAll()
            reader.close()

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
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid record: ${record.joinToString(",")}", e)
                    null
                }
            }

            // Очистка старых данных
            productDao.deleteAllProducts()
            
            // Загрузка новых данных в базу
            val entities = items.map { item ->
                ProductEntity.fromProductItem(
                    item.copy(sku = item.sku.uppercase())
                )
            }
            productDao.insertProducts(entities)

            Log.d(TAG, "Imported ${items.size} items from CSV")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing from CSV", e)
            false
        }
    }

    /**
     * Импорт данных из JSON-файла
     */
    suspend fun importFromJson(jsonFile: File, mapping: FieldMapping): Boolean {
        return try {
            val jsonString = jsonFile.readText()
            val jsonArray = JSONArray(jsonString)

            val items = (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val obj = jsonArray.getJSONObject(i)
                    ProductItem(
                        sku = obj.getString(mapping.skuFieldName),
                        name = obj.getString(mapping.nameFieldName),
                        price = if (obj.has(mapping.priceFieldName)) obj.getDouble(mapping.priceFieldName) else null,
                        category = if (obj.has(mapping.categoryFieldName)) obj.getString(mapping.categoryFieldName) else null
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid JSON object", e)
                    null
                }
            }

            // Очистка старых данных
            productDao.deleteAllProducts()
            
            // Загрузка новых данных в базу
            val entities = items.map { item ->
                ProductEntity.fromProductItem(
                    item.copy(sku = item.sku.uppercase())
                )
            }
            productDao.insertProducts(entities)

            Log.d(TAG, "Imported ${items.size} items from JSON")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing from JSON", e)
            false
        }
    }

    /**
     * Сопоставление текста с базой данных
     */
    suspend fun match(
        text: String,
        mode: MatchMode = MatchMode.AUTO,
        threshold: Int = DEFAULT_FUZZY_THRESHOLD
    ): List<MatchedItem> {
        val normalized = text.trim().uppercase()

        // Точный поиск в базе
        productDao.getProductBySku(normalized)?.let { entity ->
            val item = entity.toProductItem()
            return listOf(
                MatchedItem(
                    originalText = text,
                    matchedItem = item,
                    confidence = 100.0f,
                    boundingBox = com.arny.mlscanner.domain.models.BoundingBox(
                        0f,
                        0f,
                        0f,
                        0f
                    ) // Заглушка
                )
            )
        }

        // FTS поиск для быстрого получения кандидатов
        val ftsResults = productDao.searchProductsFts(normalized)
        
        // Fuzzy поиск среди результатов FTS для точного сопоставления
        if (mode == MatchMode.AUTO || mode == MatchMode.FUZZY) {
            val results = ftsResults
                .map { it.toProductItem() } // Convert entities to domain models
                .mapNotNull { item ->
                    val score = fuzzyMatcher.tokenSetRatio(text, item.sku)
                    if (score >= threshold) {
                        MatchedItem(
                            originalText = text,
                            matchedItem = item,
                            confidence = score.toFloat(),
                            boundingBox = com.arny.mlscanner.domain.models.BoundingBox(
                                0f,
                                0f,
                                0f,
                                0f
                            ) // Заглушка
                        )
                    } else null
                }
                .sortedByDescending { it.confidence }
                .take(5) // Возвращаем топ-5 результатов

            if (results.isNotEmpty()) {
                return results
            }
            
            // Если FTS ничего не нашел, пробуем полнотекстовый поиск
            val fallbackResults = productDao.searchProducts(normalized)
                .map { it.toProductItem() } // Convert entities to domain models
                .mapNotNull { item ->
                    val score = fuzzyMatcher.tokenSetRatio(text, item.sku)
                    if (score >= threshold) {
                        MatchedItem(
                            originalText = text,
                            matchedItem = item,
                            confidence = score.toFloat(),
                            boundingBox = com.arny.mlscanner.domain.models.BoundingBox(
                                0f,
                                0f,
                                0f,
                                0f
                            ) // Заглушка
                        )
                    } else null
                }
                .sortedByDescending { it.confidence }
                .take(5)

            if (fallbackResults.isNotEmpty()) {
                return fallbackResults
            }
        }

        // Если ничего не найдено
        return emptyList()
    }

    /**
     * Пакетное сопоставление
     */
    suspend fun batchMatch(
        texts: List<String>,
        mode: MatchMode = MatchMode.AUTO,
        threshold: Int = DEFAULT_FUZZY_THRESHOLD
    ): MatchingResult {
        val matchedItems = mutableListOf<MatchedItem>()
        val unmatchedTexts = mutableListOf<String>()

        texts.forEach { text ->
            val results = match(text, mode, threshold)
            if (results.isNotEmpty()) {
                matchedItems.addAll(results)
            } else {
                unmatchedTexts.add(text)
            }
        }

        return MatchingResult(
            matchedItems = matchedItems,
            unmatchedTexts = unmatchedTexts,
            confidenceThreshold = threshold.toFloat(),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Получение количества загруженных элементов
     */
    suspend fun getItemCount(): Int = productDao.getProductCount()
}

/**
 * Режим сопоставления
 */
enum class MatchMode {
    EXACT, FUZZY, AUTO
}

/**
 * Карта полей для импорта
 */
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

/**
 * Класс для fuzzy-сопоставления
 */
class FuzzyMatcher {
    /**
     * Token Set Ratio из FuzzyWuzzy
     * Нормирует строки, разбивает на токены и сравнивает
     */
    fun tokenSetRatio(s1: String, s2: String): Int {
        return FuzzySearch.tokenSetRatio(s1, s2)
    }
}