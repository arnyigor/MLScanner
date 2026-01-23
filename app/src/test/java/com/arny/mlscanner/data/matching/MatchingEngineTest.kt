package com.arny.mlscanner.data.matching

import com.arny.mlscanner.data.db.ProductEntity
import com.arny.mlscanner.data.db.ProductDao
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class MatchingEngineTest {

    @Mock
    private lateinit var mockProductDao: ProductDao

    private lateinit var matchingEngine: MatchingEngine

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        matchingEngine = MatchingEngine(mockProductDao)
    }

    // === Тесты импорта ===

    @Test
    fun `should import products from CSV file successfully`() = runTest {
        val csvContent = """
            sku,name,price,category
            ABC123,Test Product 1,10.99,Electronics
            XYZ789,Test Product 2,25.50,Clothing
        """.trimIndent()

        val csvFile = createTempFile(".csv", csvContent)
        val mapping = FieldMapping(skuIndex = 0, nameIndex = 1, priceIndex = 2, categoryIndex = 3)

        val success = matchingEngine.importFromCsv(csvFile, mapping)

        assertTrue(success)
        csvFile.delete()
    }

    @Test
    fun `should import products from JSON file successfully`() = runTest {
        val jsonArray = JSONArray()
        val item1 = JSONObject().apply {
            put("sku", "ABC123")
            put("name", "Test Product 1")
            put("price", 10.99)
            put("category", "Electronics")
        }
        jsonArray.put(item1)

        val jsonFile = createTempFile(".json", jsonArray.toString())
        val mapping = FieldMapping(
            skuIndex = -1,
            nameIndex = -1,
            skuFieldName = "sku",
            nameFieldName = "name"
        )
        val success = matchingEngine.importFromJson(jsonFile, mapping)

        assertTrue(success)
        jsonFile.delete()
    }

    // === Тесты поиска ===

    @Test
    fun `should find exact matches for known SKUs`() = runTest {
        val productEntity = ProductEntity(
            localId = 1,
            id = "1",
            sku = "ABC123",
            name = "Test Product",
            price = 10.99,
            category = "Electronics"
        )

        whenever(mockProductDao.getProductBySku("ABC123")).thenReturn(productEntity)

        val result = matchingEngine.match("ABC123", MatchMode.EXACT)

        assertEquals(1, result.size)
        assertEquals("ABC123", result[0].matchedItem?.sku)
        assertEquals(100.0f, result[0].confidence)
    }

    @Test
    fun `should find fuzzy matches for similar SKUs`() = runTest {
        val productEntity = ProductEntity(
            localId = 1,
            id = "1",
            sku = "ABC123",
            name = "Test Product",
            price = 10.99,
            category = "Electronics"
        )

        val inputText = "ABC124 "          // ← с пробелом (как в реальном OCR)
        val normalizedQuery = inputText.trim().uppercase() // → "ABC124"

        whenever(mockProductDao.getProductBySku(normalizedQuery)).thenReturn(null)
        whenever(mockProductDao.searchProductsFts(normalizedQuery)).thenReturn(listOf(productEntity))
        whenever(mockProductDao.searchProducts(normalizedQuery)).thenReturn(emptyList())

        val result = matchingEngine.match(inputText, MatchMode.FUZZY, threshold = 80)

        assertTrue(result.isNotEmpty())
        assertEquals("ABC123", result[0].matchedItem?.sku)
        assertTrue(result[0].confidence >= 80f)
    }

    @Test
    fun `should handle OCR text normalization properly`() = runTest {
        val productEntity = ProductEntity(
            localId = 1,
            id = "1",
            sku = "ABC-123-XYZ",
            name = "Test Product",
            price = 10.99,
            category = "Electronics"
        )

        val variations = listOf("ABC-123-XYZ", "ABC_123_XYZ", "ABC 123 XYZ", "abc-123-xyz")

        for (variation in variations) {
            val normalizedInput = variation.trim().uppercase()
            whenever(mockProductDao.getProductBySku(normalizedInput)).thenReturn(null)
            whenever(mockProductDao.searchProductsFts(normalizedInput)).thenReturn(listOf(productEntity))
            whenever(mockProductDao.searchProducts(any())).thenReturn(emptyList())
            val result = matchingEngine.match(variation, MatchMode.AUTO)
            assertTrue("Should match variation: $variation", result.isNotEmpty())
        }
    }

    // === Вспомогательные методы ===

    private fun createTempFile(suffix: String, content: String): File {
        return Files.createTempFile("test_", suffix).toFile().apply {
            writeText(content)
            deleteOnExit()
        }
    }
}