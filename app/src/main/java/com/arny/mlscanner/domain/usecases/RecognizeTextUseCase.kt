package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.TextFormatPreserver
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RecognizeTextUseCase(
    private val preprocessor: ImagePreprocessor,
    private val formatPreserver: TextFormatPreserver
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun execute(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Result<RecognizedText> = withContext(Dispatchers.Default) {
        try {
            // 1. Предобработка изображения
            val preprocessedBitmap = preprocessor.prepareBaseImage(bitmap, settings)

            // 2. Создание InputImage для ML Kit
            val inputImage = InputImage.fromBitmap(preprocessedBitmap, 0)

            // 3. Распознавание текста (suspend с помощью Tasks.await())
            val mlKitResult = recognizer.process(inputImage).await()

            // 4. Сохранение форматирования
            val recognizedText = formatPreserver.preserveFormatting(mlKitResult)

            Result.success(recognizedText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun preprocessImage(source: Bitmap, scanSettings: ScanSettings): Bitmap =
        preprocessor.prepareBaseImage(source, scanSettings)
}

// Extension для Tasks -> Coroutines
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        continuation.resume(result)
    }
    addOnFailureListener { exception ->
        continuation.resumeWithException(exception)
    }
}
