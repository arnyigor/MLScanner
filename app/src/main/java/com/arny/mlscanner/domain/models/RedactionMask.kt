package com.arny.mlscanner.domain.models

/**
 * Маска для редактирования, указывающая какие текстовые блоки нужно скрыть.
 */
data class RedactionMask(val redactedBoxes: List<TextWord>)