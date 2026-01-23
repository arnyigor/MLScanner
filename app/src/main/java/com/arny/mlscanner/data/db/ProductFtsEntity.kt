package com.arny.mlscanner.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val localId: Int = 0,

    @ColumnInfo(name = "external_id")
    val id: String = "",

    val sku: String,
    val name: String,
    val price: Double? = null,
    val category: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toProductItem(): com.arny.mlscanner.domain.models.ProductItem =
        com.arny.mlscanner.domain.models.ProductItem(
            id = id,
            sku = sku,
            name = name,
            price = price,
            category = category,
            createdAt = createdAt
        )
    companion object {
        fun fromProductItem(item: com.arny.mlscanner.domain.models.ProductItem): ProductEntity =
            ProductEntity(
                localId = 0,
                id = item.id,
                sku = item.sku,
                name = item.name,
                price = item.price,
                category = item.category,
                createdAt = item.createdAt
            )
    }
}

@Entity(tableName = "products_fts")
@Fts4(contentEntity = ProductEntity::class)
data class ProductFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Int? = null,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "sku")
    val sku: String
)
