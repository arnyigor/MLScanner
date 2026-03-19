// ============================================================
// domain/usecases/RecognizeTextUseCase.kt
// Единый UseCase для распознавания текста
// ============================================================
package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.mappers.OcrResultMapper
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.errors.OcrError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/**
 * Единый UseCase для распознавания текста.
 *
 * Ответственность:
 * 1. Оркестрация процесса (preprocessing → recognition → mapping)
 * 2. Обработка ошибок и таймаутов
 * 3. Маппинг результата в UI-модель
 *
 * НЕ ответственен за:
 * - Инициализацию движков (это делает Application/DI)
  * - Управление lifecycle движков
  * - Выбор движка (это делает OcrRepository)
  * - Предобработку изображений (это делает ImagePreprocessor)
  *
 * @property ocrRepository Репозиторий для доступа к OCR-движкам
 * @property resultMapper Маппер результатов
 * @property timeoutMs Таймаут обработки в миллисекундах
 */
class RecognizeTextUseCase(
    private val ocrRepository: OcrRepository,
    private val resultMapper: OcrResultMapper = OcrResultMapper(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        private const val TAG = "RecognizeTextUseCase"
        private const val DEFAULT_TIMEOUT_MS = 60_000L // 60 секунд
    }

    /**
     * Выполнение распознавания текста.
     *
     * @param bitmap Изображение для распознавания
     *               (уже обрезанное и масштабированное)
     * @param settings Настройки сканирования
     * @return Result<RecognizedText> — успех или типизированная ошибка
     *
     * @throws CancellationException если корутина отменена
     *         (НЕ ловится — позволяет structured concurrency работать)
     */
    suspend fun execute(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Result<RecognizedText> {
        Log.d(TAG, "Starting OCR: ${bitmap.width}x${bitmap.height}, " +
            "settings=$settings")

        return try {
            // Таймаут защищает от зависания Tesseract
            val ocrResult = withTimeout(timeoutMs) {
                ocrRepository.recognize(bitmap, settings)
            }

            Log.d(TAG, "OCR completed: ${ocrResult.summary()}")

            if (ocrResult.isEmpty) {
                Result.failure(OcrError.NoTextFound)
            } else {
                val recognized = resultMapper.toRecognizedText(ocrResult)
                Result.success(recognized)
            }

        } catch (e: CancellationException) {
            // НЕ ловим — позволяем корутине отмениться корректно
            throw e

        } catch (e: OcrError) {
            Log.e(TAG, "OCR error: ${e.displayMessage}", e)
            Result.failure(e)

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "OCR timeout after ${timeoutMs}ms", e)
            Result.failure(OcrError.Timeout(timeoutMs))

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected OCR error", e)
            Result.failure(
                OcrError.Unknown(
                    reason = e.message ?: "Unknown error",
                    cause = e
                )
            )
        }
    }
}

