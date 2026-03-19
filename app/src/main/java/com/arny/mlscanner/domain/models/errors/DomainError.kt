// ============================================================
// domain/models/errors/DomainError.kt
// Облегчённый — только релевантные типы ошибок
// ============================================================
package com.arny.mlscanner.domain.models.errors

import androidx.annotation.StringRes
import com.arny.mlscanner.domain.models.strings.StringHolder

/**
 * Базовый класс доменных ошибок.
 *
 * Для OCR-приложения оставлены только релевантные типы.
 * OCR-специфичные ошибки → OcrError (отдельный sealed class).
 *
 * @property stringHolder Пользовательское сообщение
 */
sealed class DomainError(
    open val stringHolder: StringHolder
) : Exception(stringHolder.toString()) { // ← FIX: передаём message в Exception

    /**
     * Локальная ошибка (файл не найден, нет места и т.д.)
     */
    data class Local(
        override val stringHolder: StringHolder
    ) : DomainError(stringHolder) {
        constructor(message: String) : this(StringHolder.Text(message))
    }

    /**
     * Ошибка валидации данных
     */
    data class Validation(
        override val stringHolder: StringHolder,
        val fieldName: String? = null
    ) : DomainError(stringHolder) {
        constructor(message: String, field: String? = null) :
            this(StringHolder.Text(message), field)
    }

    /**
     * Общая/неизвестная ошибка
     */
    data class Generic(
        override val stringHolder: StringHolder,
        override val cause: Throwable? = null
    ) : DomainError(stringHolder) {
        constructor(message: String?, cause: Throwable? = null) :
            this(StringHolder.Text(message ?: "Unknown error"), cause)
    }

    companion object {
        fun local(@StringRes resId: Int) = Local(StringHolder.Resource(resId))
        fun generic(@StringRes resId: Int) = Generic(StringHolder.Resource(resId))
        fun validation(@StringRes resId: Int, field: String? = null) =
            Validation(StringHolder.Resource(resId), field)
    }
}