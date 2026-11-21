package com.arny.mlscanner.data.history

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.ScanTrace
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Хранилище истории сканов: Room для метаданных и текста, изображения — файлами в
 * filesDir. Папка сканов исключена из резервного копирования Android (правило в
 * data_extraction_rules.xml), чтобы личные документы не уходили в облако.
 */
class ScanHistoryRepository(
    private val context: Context,
    private val db: ScanHistoryDatabase,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao get() = db.historyDao()

    /** Последний сохранённый скан — для замены изображения после обработки фильтром. */
    @Volatile
    private var lastSavedId: Long? = null

    val scans: Flow<List<ScanWithCodes>> = dao.observeAll()

    /** Папка с изображениями сканов. */
    private val imageDir: File
        get() = File(context.filesDir, "scans").apply { mkdirs() }

    /** Сохранить скан: метаданные, текст, коды и оба кадра. */
    suspend fun save(
        source: String,
        text: com.arny.mlscanner.domain.models.RecognizedText?,
        codes: List<BarcodeResult>,
        resultBitmap: Bitmap?,
        originalBitmap: Bitmap?,
    ): Long? = withContext(io) {
        try {
            if (text == null && codes.isEmpty()) return@withContext null
            val resultFile = resultBitmap?.savePng("scan_result")
            val originalFile = originalBitmap?.savePng("scan_original")
            val scanId = dao.insertScan(
                ScanEntity(
                    source = source,
                    createdAt = System.currentTimeMillis(),
                    imageFile = resultFile?.name,
                    originalImageFile = originalFile?.name,
                    imageWidth = (resultBitmap ?: originalBitmap)?.width ?: 0,
                    imageHeight = (resultBitmap ?: originalBitmap)?.height ?: 0,
                    confidence = text?.confidence,
                    language = text?.detectedLanguage ?: "",
                    text = text?.formattedText.orEmpty(),
                    codeCount = codes.size
                )
            )
            dao.insertCodes(
                codes.map {
                    ScanCodeEntity(
                        scanId = scanId,
                        contentType = it.contentType.name,
                        format = it.format.name,
                        rawValue = it.rawValue
                    )
                }
            )
            ScanTrace.log { "history.saved id=$scanId textChars=${text?.formattedText?.length ?: 0} codes=${codes.size}" }
            lastSavedId = scanId
            scanId
        } catch (e: Exception) {
            Log.e(TAG, "history save failed", e)
            ScanTrace.log { "history.error type=${e.javaClass.simpleName}" }
            null
        }
    }

    suspend fun load(scanId: Long): ScanWithCodes? = withContext(io) { dao.get(scanId) }

    /**
     * Заменить изображение последнего скана обработанной версией (после выбора фильтра).
     * Старый файл удаляется, размер в базе обновляется.
     */
    suspend fun replaceResultImage(bitmap: Bitmap): Boolean = withContext(io) {
        val id = lastSavedId ?: return@withContext false
        try {
            val entry = dao.get(id) ?: return@withContext false
            val scan = entry.scan
            val newFile = bitmap.savePng("scan_result")
            scan.imageFile?.let { File(imageDir, it).delete() }
            dao.insertScan(scan.copy(imageFile = newFile?.name, imageWidth = bitmap.width, imageHeight = bitmap.height))
            ScanTrace.log { "history.imageReplaced id=$id size=${bitmap.width}x${bitmap.height}" }
            true
        } catch (e: Exception) {
            Log.e(TAG, "history replace failed", e)
            false
        }
    }

    /** Загрузить сохранённый кадр; аутентичность кадру OCR не гарантируется — это кэш просмотра. */
    suspend fun loadImage(scan: ScanEntity, original: Boolean): Bitmap? = withContext(io) {
        val name = if (original) scan.originalImageFile ?: scan.imageFile else scan.imageFile
            ?: return@withContext null
        runCatching {
            android.graphics.BitmapFactory.decodeFile(File(imageDir, name).absolutePath)
        }.getOrNull()
    }

    /** Удалить скан вместе с его изображениями. */
    suspend fun delete(scanId: Long) = withContext(io) {
        val entry = dao.get(scanId)
        entry?.scan?.imageFile?.let { File(imageDir, it).delete() }
        entry?.scan?.originalImageFile?.let { File(imageDir, it).delete() }
        dao.deleteScan(scanId)
        ScanTrace.log { "history.deleted id=$scanId" }
    }

    /** Очистить всё: базу и папку изображений. */
    suspend fun clearAll() = withContext(io) {
        dao.deleteAll()
        imageDir.listFiles()?.forEach { it.delete() }
        ScanTrace.log { "history.cleared" }
    }

    private fun Bitmap.savePng(prefix: String): File? = try {
        val file = File.createTempFile("${prefix}_", ".png", imageDir)
        file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    } catch (e: Exception) {
        Log.e(TAG, "image save failed", e)
        null
    }

    private companion object {
        const val TAG = "ScanHistory"
    }
}
