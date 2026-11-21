package com.arny.mlscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.ocr.engine.TesseractEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.ScanSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * OCR реальным движком приложения на устройстве.
 *
 * Зачем отдельный тест: на эмуляторе TesseractEngine сам отключается при ARM-трансляции, поэтому
 * путь «приложение распознаёт текст» проверяется только на телефоне. Страницы и эталонный текст
 * лежат в androidTest assets/ocr. Распознанный текст пишется в filesDir/ocr, откуда его забирают
 * и сравнивают с эталоном на хосте.
 */
@RunWith(AndroidJUnit4::class)
class OcrQualityInstrumentedTest {

    private val tag = "OcrQuality"

    // Mean per-channel difference on 64x64 thumbnails: cheap and enough to spot a real edit.
    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val ta = Bitmap.createScaledBitmap(a, 64, 64, true)
        val tb = Bitmap.createScaledBitmap(b, 64, 64, true)
        val pa = IntArray(64 * 64)
        val pb = IntArray(64 * 64)
        ta.getPixels(pa, 0, 64, 0, 0, 64, 64)
        tb.getPixels(pb, 0, 64, 0, 0, 64, 64)
        var sum = 0L
        for (i in pa.indices) {
            val x = pa[i] xor pb[i]
            sum += ((x and 0xFF) + ((x shr 8) and 0xFF) + ((x shr 16) and 0xFF)).toLong()
        }
        if (ta !== a) ta.recycle()
        if (tb !== b) tb.recycle()
        return sum.toDouble() / (pa.size * 3)
    }

    @Test
    fun ocrRecognisesDistortedAndStraightPages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val context = instrumentation.targetContext
        assertTrue("OpenCV must initialise", OpenCVLoader.initLocal() || OpenCVLoader.initDebug())

        val engine = TesseractEngine(context)
        val ready = runBlocking { engine.initialize() }
        assertTrue("Tesseract must initialise on this device", ready)

        // Huawei ML Kit runs on-device and is the configured engine for Cyrillic on this phone.
        val huawei = com.arny.mlscanner.data.ocr.engine.HuaweiMLKitEngine()
        val huaweiReady = runBlocking { huawei.initialize() }
        Log.i(tag, "huawei ready=$huaweiReady")

        val preprocessor = ImagePreprocessor()
        val settings = ScanSettings(engineType = OcrEngineType.TESSERACT)
        val outDir = java.io.File(context.filesDir, "ocr")
        outDir.mkdirs()

        val pages = listOf("ru_flat", "ocr_flat", "moire_screen", "ru_tilt")
        for (name in pages) {
            val bitmap = assets.open("ocr/$name.png").use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("cannot decode $name")
            val processed = preprocessor.prepareBaseImage(bitmap, settings)

            // Compared before any OCR call: recognise() does its own image work and does not
            // promise to leave its argument untouched.
            Log.i(
                tag,
                "%s pixels identical=%s meanAbsDiff=%.3f".format(
                    name, bitmap.sameAs(processed), meanAbsDiff(bitmap, processed)
                )
            )
            java.io.FileOutputStream(java.io.File(outDir, "$name.input.png")).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            // Which stage changed the pixels? Moire removal runs on every page, so check it first.
            val moireOnly = com.arny.mlscanner.data.ocr.preprocessing.MoireRemovalPreprocessor
                .processIfNeeded(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            Log.i(
                tag,
                "%s moire_only equals_processed=%s diff_vs_input=%.3f".format(
                    name, moireOnly.sameAs(processed), meanAbsDiff(moireOnly, bitmap)
                )
            )
            java.io.FileOutputStream(java.io.File(outDir, "$name.moire.png")).use { fos ->
                moireOnly.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            if (moireOnly !== processed && moireOnly !== bitmap) moireOnly.recycle()

            val before = runBlocking { engine.recognize(bitmap, false) }
            val after = runBlocking { engine.recognize(processed, false) }
            // Reverse order on the same pixels: if the second call is always the worse one, the
            // engine leaks state between recognitions and the pipeline is not the cause.
            val after2 = runBlocking { engine.recognize(processed, false) }
            val before2 = runBlocking { engine.recognize(bitmap, false) }
            Log.i(
                tag,
                "%s before: chars=%d conf=%.1f ms=%d | after: chars=%d conf=%.1f ms=%d".format(
                    name,
                    before.fullText.length, before.averageConfidence, before.processingTimeMs,
                    after.fullText.length, after.averageConfidence, after.processingTimeMs
                )
            )
            Log.i(
                tag,
                "%s REVERSED after2: chars=%d conf=%.1f | before2: chars=%d conf=%.1f".format(
                    name, after2.fullText.length, after2.averageConfidence,
                    before2.fullText.length, before2.averageConfidence
                )
            )
            if (huaweiReady) {
                val hwBefore = runBlocking { huawei.recognize(bitmap, false) }
                val hwAfter = runBlocking { huawei.recognize(processed, false) }
                Log.i(
                    tag,
                    "%s HUAWEI before: chars=%d ms=%d | after: chars=%d ms=%d".format(
                        name, hwBefore.fullText.length, hwBefore.processingTimeMs,
                        hwAfter.fullText.length, hwAfter.processingTimeMs
                    )
                )
                java.io.File(outDir, "$name.hw_before.txt").writeText(hwBefore.fullText)
                java.io.File(outDir, "$name.hw_after.txt").writeText(hwAfter.fullText)
            }
            java.io.File(outDir, "$name.before.txt").writeText(before.fullText)
            java.io.File(outDir, "$name.after.txt").writeText(after.fullText)
            java.io.File(outDir, "$name.before2.txt").writeText(before2.fullText)
            java.io.File(outDir, "$name.after2.txt").writeText(after2.fullText)
            java.io.FileOutputStream(java.io.File(outDir, "$name.processed.png")).use { fos ->
                processed.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bitmap.recycle()
            if (processed !== bitmap) processed.recycle()
        }
        engine.release()
        if (huaweiReady) huawei.release()
    }
}
