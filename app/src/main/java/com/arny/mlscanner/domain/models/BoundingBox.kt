// ============================================================
// domain/models/BoundingBox.kt
// Координаты области текста — чистый Kotlin, без Android
// ============================================================
package com.arny.mlscanner.domain.models

/**
 * Прямоугольная область на изображении.
 * 
 * Координаты в пикселях исходного изображения.
 * Не зависит от android.graphics — можно тестировать без Android.
 *
 * @property left   Левая граница (X начала)
 * @property top    Верхняя граница (Y начала)
 * @property right  Правая граница (X конца)
 * @property bottom Нижняя граница (Y конца)
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(right >= left) {
            "right ($right) must be >= left ($left)"
        }
        require(bottom >= top) {
            "bottom ($bottom) must be >= top ($top)"
        }
    }

    /** Ширина области */
    val width: Float get() = right - left

    /** Высота области */
    val height: Float get() = bottom - top

    /** Площадь области */
    val area: Float get() = width * height

    /** Центр X */
    val centerX: Float get() = (left + right) / 2f

    /** Центр Y */
    val centerY: Float get() = (top + bottom) / 2f

    /** Пуста ли область (нулевая площадь) */
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /**
     * Проверка пересечения с другой областью.
     */
    fun intersects(other: BoundingBox): Boolean {
        return !(right <= other.left || left >= other.right ||
                bottom <= other.top || top >= other.bottom)
    }

    /**
     * Содержит ли область точку.
     */
    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }

    /**
     * Объединение двух областей (минимальный охватывающий прямоугольник).
     */
    fun union(other: BoundingBox): BoundingBox {
        return BoundingBox(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom)
        )
    }

    /**
     * Масштабирование координат.
     * Полезно при пересчёте из preview → original.
     */
    fun scale(scaleX: Float, scaleY: Float): BoundingBox {
        return BoundingBox(
            left = left * scaleX,
            top = top * scaleY,
            right = right * scaleX,
            bottom = bottom * scaleY
        )
    }

    companion object {
        /** Пустая область */
        val EMPTY = BoundingBox(0f, 0f, 0f, 0f)

        /**
         * Создание из android.graphics.Rect.
         * Единственное место конвертации Android → Domain.
         */
        fun fromRect(rect: android.graphics.Rect): BoundingBox {
            return BoundingBox(
                left = rect.left.toFloat(),
                top = rect.top.toFloat(),
                right = rect.right.toFloat(),
                bottom = rect.bottom.toFloat()
            )
        }
    }
}

/**
 * Конвертация Domain → Android.
 * Extension-функция в отдельном файле,
 * чтобы domain модуль не зависел от android.
 * 
 * ВАЖНО: Этот extension должен быть в :data или :ui модуле,
 * НЕ в :domain. Здесь для удобства в single-module проекте.
 */
fun BoundingBox.toAndroidRect(): android.graphics.Rect {
    return android.graphics.Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt()
    )
}