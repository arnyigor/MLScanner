package com.arny.mlscanner.data.ocr.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.OcrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

object OcrBenchmark {
    private const val TAG = "OcrBenchmark"
    private const val WARM_UP_RUNS = 2
    private const val BENCHMARK_RUNS = 3

    data class TestCase(
        val name: String,
        val bitmap: Bitmap,
        val expectedText: String,
        val category: String
    )

    data class BenchmarkResult(
        val engineName: String,
        val testResults: List<TestResult>,
        val averageCharAccuracy: Float,
        val averageWordAccuracy: Float,
        val averageTimeMs: Long,
        val successRate: Float,
        val initTimeMs: Long
    ) {
        data class TestResult(
            val testCase: TestCase,
            val recognizedText: String,
            val charAccuracy: Float,
            val wordAccuracy: Float,
            val timeMs: Long,
            val confidence: Float,
            val success: Boolean,
            val error: String? = null
        )
    }

    suspend fun runBenchmark(
        context: Context,
        ocrRepository: OcrRepository,
        onProgress: (String) -> Unit
    ): Map<String, BenchmarkResult> = withContext(Dispatchers.Default) {
        val results = mutableMapOf<String, BenchmarkResult>()

        onProgress("Генерация тестовых изображений...")
        val testCases = generateTestCases()

        onProgress("Инициализация OCR движков...")
        val initResults = ocrRepository.initialize()

        val engines = listOf(
            "ML Kit" to "MLKIT",
            "Tesseract" to "TESSERACT",
            "Hybrid" to "HYBRID"
        )

        for ((engineName, engineKey) in engines) {
            val initSuccess = initResults[engineName] == true
            if (!initSuccess) {
                Log.w(TAG, "$engineName not available, skipping")
                continue
            }

            onProgress("Тестирование: $engineName")
            val result = benchmarkEngine(
                context, engineName, engineKey, testCases, ocrRepository
            ) { status -> onProgress(status) }
            results[engineName] = result
        }

        results
    }

    private suspend fun benchmarkEngine(
        context: Context,
        engineName: String,
        engineKey: String,
        testCases: List<TestCase>,
        ocrRepository: OcrRepository,
        onProgress: (String) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.Default) {
        val imagePreprocessor = ImagePreprocessor()
        val testResults = mutableListOf<BenchmarkResult.TestResult>()
        var totalCharAcc = 0f
        var totalWordAcc = 0f
        var totalTime = 0L
        var successCount = 0

        for ((index, testCase) in testCases.withIndex()) {
            onProgress("  ${index + 1}/${testCases.size}: ${testCase.name}")

            val processedBitmap = imagePreprocessor.prepareBaseImage(
                testCase.bitmap,
                ScanSettings()
            )

            val times = mutableListOf<Long>()
            var lastResult: OcrResult? = null

            // Warm-up
            repeat(WARM_UP_RUNS) {
                try {
                    ocrRepository.recognizeWith(processedBitmap, engineKey, ScanSettings())
                } catch (_: Exception) { }
            }

            // Benchmark runs
            repeat(BENCHMARK_RUNS) {
                try {
                    val start = System.currentTimeMillis()
                    val result = ocrRepository.recognizeWith(processedBitmap, engineKey, ScanSettings())
                    times.add(System.currentTimeMillis() - start)
                    lastResult = result
                } catch (e: Exception) {
                    Log.w(TAG, "Run $it failed", e)
                }
            }

            if (processedBitmap !== testCase.bitmap) {
                processedBitmap.recycle()
            }

            if (lastResult != null) {
                val avgTime = if (times.isNotEmpty()) times.average().toLong() else 0L
                val charAcc = charAccuracy(testCase.expectedText, lastResult!!.fullText)
                val wordAcc = wordAccuracy(testCase.expectedText, lastResult!!.fullText)

                testResults.add(BenchmarkResult.TestResult(
                    testCase = testCase,
                    recognizedText = lastResult!!.fullText,
                    charAccuracy = charAcc,
                    wordAccuracy = wordAcc,
                    timeMs = avgTime,
                    confidence = lastResult!!.averageConfidence,
                    success = true
                ))

                totalCharAcc += charAcc
                totalWordAcc += wordAcc
                totalTime += avgTime
                successCount++
            } else {
                testResults.add(BenchmarkResult.TestResult(
                    testCase = testCase,
                    recognizedText = "",
                    charAccuracy = 0f,
                    wordAccuracy = 0f,
                    timeMs = 0,
                    confidence = 0f,
                    success = false,
                    error = "All runs failed"
                ))
            }
        }

        val count = testResults.size.toFloat()
        BenchmarkResult(
            engineName = engineName,
            testResults = testResults,
            averageCharAccuracy = if (successCount > 0) totalCharAcc / successCount else 0f,
            averageWordAccuracy = if (successCount > 0) totalWordAcc / successCount else 0f,
            averageTimeMs = if (successCount > 0) totalTime / successCount else 0L,
            successRate = successCount / count,
            initTimeMs = 0
        )
    }

    fun generateTestCases(): List<TestCase> {
        val cases = mutableListOf<TestCase>()

        cases.add(createTextTest("Простой русский", "Привет мир", 48f, "Русский"))
        cases.add(createTextTest("Русское предложение", "Съешь ещё этих мягких булок", 32f, "Русский"))
        cases.add(createTextTest("Номер телефона", "+7 999 123 45 67", 40f, "Цифры"))
        cases.add(createTextTest("Email", "test@example.com", 36f, "Смешанный"))
        cases.add(createTextTest("Английский текст", "Hello World OCR", 40f, "Английский"))
        cases.add(createTextTest("Смешанный", "Model iPhone 15 Pro", 32f, "Смешанный"))

        return cases
    }

    private fun createTextTest(
        name: String,
        text: String,
        fontSize: Float,
        category: String
    ): TestCase {
        val width = maxOf(600, (text.length * fontSize * 0.7).toInt() + 100)
        val height = (fontSize * 2.5).toInt() + 20

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = fontSize
            isAntiAlias = true
            typeface = Typeface.DEFAULT
        }

        canvas.drawText(text, 20f, fontSize + 10f, paint)
        return TestCase(name, bitmap, text, category)
    }

    private fun charAccuracy(expected: String, actual: String): Float {
        val e = expected.lowercase().trim()
        val a = actual.lowercase().trim()
        if (e.isEmpty()) return if (a.isEmpty()) 1f else 0f
        val dist = levenshtein(e, a)
        return (1f - dist.toFloat() / maxOf(e.length, a.length)).coerceAtLeast(0f)
    }

    private fun wordAccuracy(expected: String, actual: String): Float {
        val ew = expected.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val aw = actual.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (ew.isEmpty()) return if (aw.isEmpty()) 1f else 0f

        var matched = 0
        val remaining = aw.toMutableList()
        for (w in ew) {
            val idx = remaining.indexOfFirst { levenshtein(it, w) <= 1 }
            if (idx >= 0) { matched++; remaining.removeAt(idx) }
        }
        return matched.toFloat() / ew.size
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + min(min(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1])
        }
        return dp[m][n]
    }

    fun generateReport(results: Map<String, BenchmarkResult>): String = buildString {
        appendLine("╔════════════════════════════════════════════════════╗")
        appendLine("║          ОТЧЁТ СРАВНИТЕЛЬНОГО ТЕСТИРОВАНИЯ OCR    ║")
        appendLine("╚════════════════════════════════════════════════════╝")
        appendLine()

        appendLine("┌────────────────────────────────────────────────────┐")
        appendLine("│                  СВОДНАЯ ТАБЛИЦА                    │")
        appendLine("├──────────────┬────────┬────────┬────────┬────────────┤")
        appendLine("│ Движок       │Точность│Точность│ Время  │  Успешность│")
        appendLine("│              │(символ)│(слово) │  (мс)  │            │")
        appendLine("├──────────────┼────────┼────────┼────────┼────────────┤")

        for ((name, result) in results) {
            appendLine(String.format(
                "│ %-12s │ %5.1f%% │ %5.1f%% │ %5dms │   %3d%%      │",
                name.take(12),
                result.averageCharAccuracy * 100,
                result.averageWordAccuracy * 100,
                result.averageTimeMs,
                (result.successRate * 100).toInt()
            ))
        }
        appendLine("└──────────────┴────────┴────────┴────────┴────────────┘")

        for ((name, result) in results) {
            appendLine("\n═══ $name ═══")
            for (test in result.testResults) {
                val icon = when {
                    !test.success -> "💥"
                    test.charAccuracy >= 0.9f -> "✅"
                    test.charAccuracy >= 0.7f -> "⚠️"
                    else -> "❌"
                }
                appendLine("  $icon ${test.testCase.name}: ${(test.charAccuracy * 100).toInt()}%")
                if (test.charAccuracy < 0.9f && test.success) {
                    appendLine("     Ожидание: \"${test.testCase.expectedText}\"")
                    appendLine("     Получено: \"${test.recognizedText}\"")
                }
            }
        }

        val best = results.values.filter { it.successRate > 0 }
            .maxByOrNull {
                it.averageCharAccuracy * 0.6f + (1f - it.averageTimeMs / 5000f) * 0.3f + it.successRate * 0.1f
            }

        if (best != null) {
            appendLine("\n🏆 Рекомендация: ${best.engineName}")
        }
    }
}
