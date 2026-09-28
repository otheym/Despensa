package com.casa.despensa.data


import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Identificador universal: el mismo producto tiene el mismo uid en todos los móviles. */
fun newUid(): String = UUID.randomUUID().toString()

/**
 * Producto de la despensa.
 *
 * - [id] es local de cada móvil; [uid] es el identificador compartido para sincronizar.
 * - [quantity] es una caché: la cantidad real es la suma de los movimientos ([StockEvent]).
 *   Así, al sincronizar, los cambios hechos en varios móviles se suman y ninguno se pierde.
 * - [metaUpdatedAt] marca la última edición de nombre o código: al sincronizar gana la más reciente.
 * - El precio se guarda en céntimos. El estado del semáforo se deriva de la cantidad.
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["barcode"], unique = true),
        Index(value = ["name"]),
    ],
)
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val name: String,
    val barcode: String? = null,
    @ColumnInfo(name = "last_price_cents") val lastPriceCents: Long? = null,
    val quantity: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "meta_updated_at") val metaUpdatedAt: Long = System.currentTimeMillis(),
)

/**
 * Movimiento de stock (+2 compra, −1 consumo…). Inmutable: nunca se modifica ni se borra,
 * solo se añade. La cantidad de un producto = suma de sus movimientos.
 */
@Entity(
    tableName = "stock_events",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["product_id"]),
    ],
)
data class StockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    @ColumnInfo(name = "product_id") val productId: Long,
    val delta: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Estado de stock. El orden de declaración ES el orden de la interfaz: Rojo → Amarillo → Verde. */
enum class StockStatus {
    RED,     // cantidad == 0      → agotado, va a la lista de la compra
    YELLOW,  // cantidad 1..2      → queda poco
    GREEN;   // cantidad > 2       → stock suficiente

    companion object {
        const val LOW_STOCK_MAX = 2
        const val GREEN_MIN = LOW_STOCK_MAX + 1

        fun from(quantity: Int): StockStatus = when {
            quantity <= 0 -> RED
            quantity <= LOW_STOCK_MAX -> YELLOW
            else -> GREEN
        }
    }
}

val Product.status: StockStatus get() = StockStatus.from(quantity)