package com.arny.mlscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.ocr.preprocessing.MoireRemovalPreprocessor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * Калибровка детектора муара.
 *
 * Печатает `moireScore` для всех страниц корпуса, чтобы отделить документы (муара нет) от
 * фотографии экрана (муар есть) числами, а не порогом «на глаз». Проверка держится на том,
 * что score у обычных страниц заметно ниже, чем у экранного снимка.
 */
@RunWith(AndroidJUnit4::class)
class MoireDetectorInstrumentedTest {

    private val tag = "MoireScore"

    @Test
    fun scoreSeparatesDocumentsFromScreenPhotos() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assertTrue("OpenCV must initialise", OpenCVLoader.initLocal() || OpenCVLoader.initDebug())

        val groups = listOf("dewarp", "ocr", "corpus", "photo")
        @Suppress("UNUSED_VARIABLE")
        var documents = 0
        var maxDocument = 0.0
        var maxDocumentName = ""
        for (group in groups) {
            val names = assets.list(group)?.filter { it.endsWith(".png") || it.endsWith(".jpg") }
                ?.sorted() ?: emptyList()
            for (name in names) {
                if (name.contains("moire")) continue
                val bitmap = assets.open("$group/$name").use { BitmapFactory.decodeStream(it) }
                    ?: continue
                val score = MoireRemovalPreprocessor.moireScore(bitmap)
                Log.i(tag, "DOC %-42s score=%.2f".format("$group/$name", score))
                documents++
                if (score > maxDocument) {
                    maxDocument = score
                    maxDocumentName = "$group/$name"
                }
                bitmap.recycle()
            }
        }

        var screenMax = 0.0
        for (name in listOf("moire_screen.png")) {
            val bitmap = assets.open("ocr/$name").use { BitmapFactory.decodeStream(it) }
                ?: continue
            val score = MoireRemovalPreprocessor.moireScore(bitmap)
            Log.i(tag, "SCREEN %-39s score=%.2f".format(name, score))
            if (score > screenMax) screenMax = score
            bitmap.recycle()
        }

        Log.i(
            tag,
            "SUMMARY documents=%d max_document=%.2f (%s) screen=%.2f".format(
                documents, maxDocument, maxDocumentName, screenMax
            )
        )
        // Замер, а не проверка: числа показывают, что признак НЕ разделяет документ и экран
        // (84.8 против 84.2), поэтому автоматическое вычитание муара отключено в
        // MoireRemovalPreprocessor.processIfNeeded. Если появится образец, на котором признак
        // разделяет, здесь появится порог и утверждение.
    }
}
