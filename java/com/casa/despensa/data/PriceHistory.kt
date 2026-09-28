package com.casa.despensa.data



import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// ============================================================
//  Entidades
// ============================================================

/**
 * Tienda / lugar de compra. NOCASE en la columna hace que el índice único
 * y las comparaciones con "=" ignoren mayúsculas: "Mercadona" == "mercadona".
 */
@Entity(
    tableName = "stores",
    indices = [Index(value = ["name"], unique = true)],
)
data class Store(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

/**
 * Un punto del historial de precios: producto + tienda + precio + fecha (epoch millis).
 * - Borrar un producto borra su historial (CASCADE).
 * - No se puede borrar una tienda con historial (RESTRICT).
 */
@Entity(
    tableName = "price_records",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Store::class,
            parentColumns = ["id"],
            childColumns = ["store_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["product_id", "recorded_at"]),
        Index(value = ["store_id"]),
    ],
)
data class PriceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)

/** Resultado listo para la gráfica: precio, fecha y nombre de tienda. */
data class PricePoint(
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "store_name") val storeName: String,
)

// ============================================================
//  DAO
// ============================================================

@Dao
abstract class PriceDao {

    // ---------- Tiendas ----------

    @Query("SELECT * FROM stores ORDER BY name COLLATE NOCASE ASC")
    abstract fun observeStores(): Flow<List<Store>>

    /** Última tienda usada en cualquier producto: sirve para prerrellenar el diálogo. */
    @Query(
        """
        SELECT s.name FROM price_records r
        INNER JOIN stores s ON s.id = r.store_id
        ORDER BY r.recorded_at DESC, r.id DESC
        LIMIT 1
        """
    )
    abstract fun observeLastStoreName(): Flow<String?>

    @Query("SELECT id FROM stores WHERE name = :name LIMIT 1")
    abstract suspend fun findStoreId(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertStore(store: Store): Long

    // ---------- Historial ----------

    @Insert
    abstract suspend fun insertRecord(record: PriceRecord): Long

    /** Serie temporal de un producto, de más antiguo a más reciente (orden natural de una gráfica). */
    @Query(
        """
        SELECT r.price_cents, r.recorded_at, s.name AS store_name
        FROM price_records r
        INNER JOIN stores s ON s.id = r.store_id
        WHERE r.product_id = :productId
        ORDER BY r.recorded_at ASC, r.id ASC
        """
    )
    abstract fun observeHistory(productId: Long): Flow<List<PricePoint>>

    /** Productos con al menos un precio registrado: para el selector de la pestaña de gráficas. */
    @Query(
        """
        SELECT p.* FROM products p
        WHERE EXISTS (SELECT 1 FROM price_records r WHERE r.product_id = p.id)
        ORDER BY p.name COLLATE NOCASE ASC
        """
    )
    abstract fun observeProductsWithHistory(): Flow<List<Product>>

    @Query("UPDATE products SET last_price_cents = :priceCents, updated_at = :now WHERE id = :productId")
    abstract suspend fun setProductLastPrice(productId: Long, priceCents: Long?, now: Long)

    /**
     * Registra un precio de forma atómica:
     * 1) busca o crea la tienda, 2) añade el punto al historial, 3) actualiza el último precio del producto.
     */
    @Transaction
    open suspend fun recordPrice(productId: Long, priceCents: Long, storeName: String, now: Long) {
        val cleanStore = storeName.trim()
        require(cleanStore.isNotEmpty()) { "El lugar de compra es obligatorio" }
        val storeId = findStoreId(cleanStore) ?: insertStore(Store(name = cleanStore))
        insertRecord(
            PriceRecord(
                productId = productId,
                storeId = storeId,
                priceCents = priceCents,
                recordedAt = now,
            )
        )
        setProductLastPrice(productId, priceCents, now)
    }
}