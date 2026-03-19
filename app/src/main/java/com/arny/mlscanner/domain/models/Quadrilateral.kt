package com.arny.mlscanner.domain.models

import android.graphics.PointF

/**
 * Представление четырехугольника (например, контура документа).
 * Точки хранятся в порядке: верх-лево, верх-право, низ-право, низ-лево.
 */
data class Quadrilateral(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    val points: Array<PointF> = arrayOf(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * Проверяет, что四角形是有效的（非退化）。
     * Простая проверка: все точки различны и площадь > 0.
     */
    val isValid: Boolean
        get() {
            // Проверяем, что все точки различны (простая проверка)
            if (topLeft == topRight || topLeft == bottomLeft || topLeft == bottomRight ||
                topRight == bottomLeft || topRight == bottomRight ||
                bottomLeft == bottomRight) {
                return false
            }
            // Вычисляем площадь через векторное произведение (площадь четырехугольника)
            val area = Math.abs(
                (topLeft.x * topRight.y + topRight.x * bottomRight.y +
                        bottomRight.x * bottomLeft.y + bottomLeft.x * topLeft.y) -
                        (topRight.x * topLeft.y + bottomRight.x * topRight.y +
                                bottomLeft.x * bottomRight.y + topLeft.x * bottomLeft.y)
            ) / 2.0
            return area > 0.1f // Минимальная площадь, чтобы избежать вырожденных случаев
        }
}