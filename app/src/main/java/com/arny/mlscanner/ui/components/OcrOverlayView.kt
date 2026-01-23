package com.arny.mlscanner.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.TextBox

/**
 * Компонент для отображения результатов OCR поверх превью камеры
 * В соответствии с требованиями TECH.md:
 * - Отображение найденных данных на фото
 * - Ручное выделение области (свободное рисование)
 * - Toggle "Hide/Show" для каждого найденного блока
 * - Предпросмотр финального PDF
 */
class OcrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val detectedBoxesPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.GREEN
        isAntiAlias = true
    }
    
    private val redactedBoxesPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#80FF0000") // Полупрозрачный красный
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    private val redactedTextPaint = Paint().apply {
        color = Color.GRAY
        textSize = 36f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    
    private val highlightPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.YELLOW
        isAntiAlias = true
    }
    
    private var textBoxes: List<TextBox> = emptyList()
    private var redactedBoxes: List<BoundingBox> = emptyList()
    private var highlightedBox: BoundingBox? = null
    
    /**
     * Установка результатов OCR для отображения
     */
    fun setTextBoxes(textBoxes: List<TextBox>) {
        this.textBoxes = textBoxes
        invalidate()
    }
    
    /**
     * Установка областей для редактирования
     */
    fun setRedactedBoxes(redactedBoxes: List<BoundingBox>) {
        this.redactedBoxes = redactedBoxes
        invalidate()
    }
    
    /**
     * Установка выделенной области
     */
    fun setHighlightedBox(box: BoundingBox?) {
        this.highlightedBox = box
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Рисуем области редактирования (красные полупрозрачные)
        for (box in redactedBoxes) {
            val rect = Rect(
                box.left.toInt(),
                box.top.toInt(),
                box.right.toInt(),
                box.bottom.toInt()
            )
            canvas.drawRect(rect, redactedBoxesPaint)
        }
        
        // Рисуем обнаруженные текстовые блоки
        for (textBox in textBoxes) {
            val box = textBox.boundingBox
            val rect = Rect(
                box.left.toInt(),
                box.top.toInt(),
                box.right.toInt(),
                box.bottom.toInt()
            )
            
            // Выбираем цвет в зависимости от уверенности
            detectedBoxesPaint.color = when {
                textBox.confidence > 0.8f -> Color.GREEN
                textBox.confidence > 0.5f -> Color.YELLOW
                else -> Color.RED
            }
            
            // Рисуем рамку
            canvas.drawRect(rect, detectedBoxesPaint)
            
            // Рисуем текст рядом с рамкой
            val textPaintToUse = if (redactedBoxes.any { it.intersects(box) }) {
                redactedTextPaint
            } else {
                textPaint
            }
            
            canvas.drawText(
                textBox.text,
                rect.left.toFloat(),
                (rect.top - 10).toFloat(),
                textPaintToUse
            )
        }
        
        // Рисуем выделенную рамку поверх остальных
        highlightedBox?.let { box ->
            val rect = Rect(
                box.left.toInt(),
                box.top.toInt(),
                box.right.toInt(),
                box.bottom.toInt()
            )
            canvas.drawRect(rect, highlightPaint)
        }
    }
    
    /**
     * Метод для получения текстового блока по координатам касания
     */
    fun getTextBoxAt(x: Float, y: Float): TextBox? {
        return textBoxes.find { box ->
            x >= box.boundingBox.left && x <= box.boundingBox.right &&
            y >= box.boundingBox.top && y <= box.boundingBox.bottom
        }
    }
    
    /**
     * Метод для получения области редактирования по координатам касания
     */
    fun getRedactedBoxAt(x: Float, y: Float): BoundingBox? {
        return redactedBoxes.find { box ->
            x >= box.left && x <= box.right &&
            y >= box.top && y <= box.bottom
        }
    }
}