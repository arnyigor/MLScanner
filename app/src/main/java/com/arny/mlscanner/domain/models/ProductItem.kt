package com.arny.mlscanner.domain.models

/**
 * Элемент продукта (для модуля сопоставления)
 */
data class ProductItem(
    val id: String = "",
    val sku: String,
    val name: String,
    val price: Double? = null,
    val category: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)