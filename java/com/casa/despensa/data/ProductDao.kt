package com.casa.despensa.data


import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Operaciones sobre productos.
 *
 * REGLA: la cantidad solo se modifica con [changeQuantity] (o los métodos que la usan),
 * que registra el movimiento en stock_events. Así la sincronización puede sumar
 * los cambios de varios móviles sin perder ninguno.
 */
@Dao
abstract class ProductDao {

    // ---------- Lecturas reactivas ----------

    /** Ordenar por cantidad ascendente ya produce Rojo → Amarillo → Verde. */
    @Query("SELECT * FROM products ORDER BY quantity ASC, name COLLATE NOCASE ASC")
    abstract fun observeAll(): Flow<List<Product>>

    @Query(
        """
        SELECT * FROM products
        WHERE name LIKE '%' || :query || '%' OR barcode = :query
        ORDER BY quantity ASC, name COLLATE NOCASE ASC
        """
    )
    abstract fun search(query: String): Flow<List<Product>>

    /** maxQuantity = 0 → solo rojos; maxQuantity = LOW_STOCK_MAX → rojos y amarillos. */
    @Query(
        """
        SELECT * FROM products
        WHERE quantity <= :maxQuantity
        ORDER BY quantity ASC, name COLLATE NOCASE ASC
        """
    )
    abstract fun observeShoppingList(maxQuantity: Int): Flow<List<Product>>

    // ---------- Lecturas puntuales ----------

    @Query("SELECT * FROM products WHERE id = :id")
    abstract suspend fun findById(id: Long): Product?

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    abstract suspend fun findByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findByName(name: String): Product?

    @Query("SELECT quantity FROM products WHERE id = :id")
    abstract suspend fun quantityOf(id: Long): Int?

    // ---------- Escrituras de bajo nivel (usar los métodos @Transaction de abajo) ----------

    /** No usar directamente: la cantidad inicial no quedaría registrada. Usa [insertWithStock]. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRaw(product: Product): Long

    @Insert
    abstract suspend fun insertEvent(event: StockEvent)

    @Query("UPDATE products SET quantity = :quantity, updated_at = :now WHERE id = :id")
    abstract suspend fun writeQuantity(id: Long, quantity: Int, now: Long)

    @Delete
    abstract suspend fun delete(product: Product)

    /** Cambiar el nombre cuenta como edición: gana en la sincronización si es la más reciente. */
    @Query("UPDATE products SET name = :name, updated_at = :now, meta_updated_at = :now WHERE id = :id")
    abstract suspend fun updateName(id: Long, name: String, now: Long)

    // ---------- Operaciones de stock (siempre con movimiento) ----------

    /**
     * Suma [delta] sin bajar de 0, registra el movimiento realmente aplicado y
     * actualiza la caché de cantidad. Devuelve el delta aplicado (0 si no hubo cambio).
     */
    @Transaction
    open suspend fun changeQuantity(id: Long, delta: Int, now: Long): Int {
        val current = quantityOf(id) ?: return 0
        val target = (current + delta).coerceAtLeast(0)
        val applied = target - current
        if (applied != 0) {
            insertEvent(StockEvent(productId = id, delta = applied, createdAt = now))
            writeQuantity(id, target, now)
        }
        return applied
    }

    /** Fija una cantidad exacta (p. ej. deshacer), registrándolo como movimiento. */
    @Transaction
    open suspend fun setQuantity(id: Long, quantity: Int, now: Long) {
        val current = quantityOf(id) ?: return
        changeQuantity(id, quantity.coerceAtLeast(0) - current, now)
    }

    /** Compra: suma [bought] unidades y garantiza al menos [minQuantity]. */
    @Transaction
    open suspend fun registerPurchase(id: Long, bought: Int, minQuantity: Int, now: Long) {
        val current = quantityOf(id) ?: return
        val target = maxOf(current + bought, minQuantity)
        changeQuantity(id, target - current, now)
    }

    /** Alta de producto con su cantidad inicial registrada como primer movimiento. */
    @Transaction
    open suspend fun insertWithStock(product: Product, now: Long): Long {
        val id = insertRaw(product.copy(quantity = 0))
        if (product.quantity > 0) changeQuantity(id, product.quantity, now)
        return id
    }

    /** Escaneo de código de barras: +1 si existe. Devuelve el producto actualizado o null. */
    @Transaction
    open suspend fun incrementByBarcode(barcode: String, now: Long): Product? {
        val product = findByBarcode(barcode) ?: return null
        changeQuantity(product.id, +1, now)
        return findById(product.id)
    }
}