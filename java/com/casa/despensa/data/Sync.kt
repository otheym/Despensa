package com.casa.despensa.data



import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// ============================================================
//  Modelo del fichero de sincronización
// ============================================================

data class SyncProduct(
    val uid: String,
    val name: String,
    val barcode: String?,
    val metaUpdatedAt: Long,
)

/** Movimiento de stock referido al uid del producto (los id locales no viajan). */
data class SyncEvent(
    @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "product_uid") val productUid: String,
    @ColumnInfo(name = "delta") val delta: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Precio del historial. Se identifica por producto + tienda + precio + instante exacto. */
data class SyncPrice(
    @ColumnInfo(name = "product_uid") val productUid: String,
    @ColumnInfo(name = "store_name") val storeName: String,
    @ColumnInfo(name = "price_cents") val priceCents: Long,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)

data class SyncData(
    val deviceName: String,
    val exportedAt: Long,
    val products: List<SyncProduct>,
    val events: List<SyncEvent>,
    val stores: List<String>,
    val prices: List<SyncPrice>,
)

data class ImportSummary(
    val sourceDevice: String,
    val exportedAt: Long,
    val newProducts: Int,
    val updatedProducts: Int,
    val newMovements: Int,
    val newPrices: Int,
) {
    val nothingNew: Boolean
        get() = newProducts == 0 && updatedProducts == 0 && newMovements == 0 && newPrices == 0
}

class SyncFormatException(message: String) : Exception(message)

// ============================================================
//  DAO de sincronización
// ============================================================

@Dao
abstract class SyncDao {

    // ---------- Exportar ----------

    @Query("SELECT * FROM products")
    abstract suspend fun allProducts(): List<Product>

    @Query(
        """
        SELECT e.uid AS uid, p.uid AS product_uid, e.delta AS delta, e.created_at AS created_at
        FROM stock_events e INNER JOIN products p ON p.id = e.product_id
        """
    )
    abstract suspend fun allEvents(): List<SyncEvent>

    @Query("SELECT name FROM stores")
    abstract suspend fun allStoreNames(): List<String>

    @Query(
        """
        SELECT p.uid AS product_uid, s.name AS store_name,
               r.price_cents AS price_cents, r.recorded_at AS recorded_at
        FROM price_records r
        INNER JOIN products p ON p.id = r.product_id
        INNER JOIN stores s ON s.id = r.store_id
        """
    )
    abstract suspend fun allPrices(): List<SyncPrice>

    // ---------- Importar: consultas auxiliares ----------

    @Query("SELECT * FROM products WHERE uid = :uid LIMIT 1")
    abstract suspend fun findProductByUid(uid: String): Product?

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    abstract suspend fun findProductByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findProductByName(name: String): Product?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProduct(product: Product): Long

    @Query("UPDATE products SET uid = :uid WHERE id = :id")
    abstract suspend fun setProductUid(id: Long, uid: String)

    @Query("UPDATE products SET name = :name, barcode = :barcode, meta_updated_at = :metaUpdatedAt WHERE id = :id")
    abstract suspend fun updateProductMeta(id: Long, name: String, barcode: String?, metaUpdatedAt: Long)

    @Query("SELECT COUNT(*) FROM stock_events WHERE uid = :uid")
    abstract suspend fun countEvents(uid: String): Int

    @Insert
    abstract suspend fun insertEvent(event: StockEvent)

    /** Recalcula la caché de cantidad de todos los productos a partir de sus movimientos. */
    @Query(
        """
        UPDATE products SET quantity = MAX(0,
            (SELECT COALESCE(SUM(e.delta), 0) FROM stock_events e WHERE e.product_id = products.id))
        """
    )
    abstract suspend fun recomputeQuantities()

    @Query("SELECT id FROM stores WHERE name = :name LIMIT 1")
    abstract suspend fun findStoreId(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertStore(store: Store): Long

    @Query(
        """
        SELECT COUNT(*) FROM price_records
        WHERE product_id = :productId AND store_id = :storeId
          AND price_cents = :priceCents AND recorded_at = :recordedAt
        """
    )
    abstract suspend fun countPrices(productId: Long, storeId: Long, priceCents: Long, recordedAt: Long): Int

    @Insert
    abstract suspend fun insertPrice(record: PriceRecord)

    /** El último precio del producto pasa a ser el del registro más reciente del historial. */
    @Query(
        """
        UPDATE products SET last_price_cents = (
            SELECT r.price_cents FROM price_records r
            WHERE r.product_id = products.id
            ORDER BY r.recorded_at DESC, r.id DESC LIMIT 1)
        WHERE id = :productId
        """
    )
    abstract suspend fun refreshLastPrice(productId: Long)

    private suspend fun storeIdFor(name: String): Long =
        findStoreId(name.trim()) ?: insertStore(Store(name = name.trim()))

    // ---------- Fusión ----------

    /**
     * Fusiona el contenido de otro móvil con el local. Es idempotente y conmutativa:
     * importar dos veces el mismo fichero, o ficheros en cualquier orden, da el mismo resultado.
     *
     * - Productos: se emparejan por uid, luego por código de barras y luego por nombre.
     *   Si se emparejan dos uid distintos, ambos móviles adoptan el menor, así convergen.
     *   Nombre: gana la edición más reciente. Código de barras: nunca se pierde uno existente.
     * - Movimientos de stock: se añaden los que falten (por uid) y se recalcula la cantidad.
     * - Tiendas y precios: se añaden los que falten.
     */
    @Transaction
    open suspend fun merge(data: SyncData, now: Long): ImportSummary {
        var newProducts = 0
        var updatedProducts = 0
        var newMovements = 0
        var newPrices = 0
        val localIdByRemoteUid = HashMap<String, Long>()

        // 1) Productos
        for (remote in data.products) {
            val remoteBarcode = remote.barcode?.takeIf { it.isNotBlank() }
            val local = findProductByUid(remote.uid)
                ?: remoteBarcode?.let { findProductByBarcode(it) }
                ?: findProductByName(remote.name)

            if (local == null) {
                val id = insertProduct(
                    Product(
                        uid = remote.uid,
                        name = remote.name,
                        barcode = remoteBarcode,
                        quantity = 0, // la cantidad llega con los movimientos
                        updatedAt = now,
                        metaUpdatedAt = remote.metaUpdatedAt,
                    )
                )
                localIdByRemoteUid[remote.uid] = id
                newProducts++
                continue
            }

            localIdByRemoteUid[remote.uid] = local.id
            if (remote.uid != local.uid && remote.uid < local.uid) {
                setProductUid(local.id, remote.uid)
            }

            val remoteIsNewer = remote.metaUpdatedAt > local.metaUpdatedAt
            val name = if (remoteIsNewer) remote.name else local.name
            // Un código de barras se adopta si está libre; nunca se borra el que ya había.
            val candidateBarcode = if (remoteIsNewer) remoteBarcode ?: local.barcode else local.barcode ?: remoteBarcode
            val barcode = candidateBarcode?.let { code ->
                val owner = findProductByBarcode(code)
                if (owner == null || owner.id == local.id) code else local.barcode
            }
            val metaAt = maxOf(local.metaUpdatedAt, remote.metaUpdatedAt)
            if (name != local.name || barcode != local.barcode) {
                updateProductMeta(local.id, name, barcode, metaAt)
                updatedProducts++
            }
        }

        // 2) Movimientos de stock
        for (event in data.events) {
            val productId = localIdByRemoteUid[event.productUid] ?: continue
            if (countEvents(event.uid) == 0) {
                insertEvent(
                    StockEvent(
                        uid = event.uid,
                        productId = productId,
                        delta = event.delta,
                        createdAt = event.createdAt,
                    )
                )
                newMovements++
            }
        }
        if (newMovements > 0) recomputeQuantities()

        // 3) Tiendas
        data.stores.filter { it.isNotBlank() }.forEach { storeIdFor(it) }

        // 4) Precios
        val touched = HashSet<Long>()
        for (price in data.prices) {
            val productId = localIdByRemoteUid[price.productUid] ?: continue
            if (price.storeName.isBlank()) continue
            val storeId = storeIdFor(price.storeName)
            if (countPrices(productId, storeId, price.priceCents, price.recordedAt) == 0) {
                insertPrice(
                    PriceRecord(
                        productId = productId,
                        storeId = storeId,
                        priceCents = price.priceCents,
                        recordedAt = price.recordedAt,
                    )
                )
                touched += productId
                newPrices++
            }
        }
        touched.forEach { refreshLastPrice(it) }

        return ImportSummary(
            sourceDevice = data.deviceName,
            exportedAt = data.exportedAt,
            newProducts = newProducts,
            updatedProducts = updatedProducts,
            newMovements = newMovements,
            newPrices = newPrices,
        )
    }
}

// ============================================================
//  Codificación JSON (org.json, incluido en Android)
// ============================================================

object SyncCodec {
    private const val FORMAT = "despensa-sync"
    private const val VERSION = 1

    fun encode(data: SyncData): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("device", data.deviceName)
            .put("exportedAt", data.exportedAt)

        root.put("products", JSONArray().apply {
            data.products.forEach { p ->
                put(
                    JSONObject()
                        .put("uid", p.uid)
                        .put("name", p.name)
                        .put("barcode", p.barcode ?: JSONObject.NULL)
                        .put("metaUpdatedAt", p.metaUpdatedAt)
                )
            }
        })
        root.put("events", JSONArray().apply {
            data.events.forEach { e ->
                put(
                    JSONObject()
                        .put("uid", e.uid)
                        .put("product", e.productUid)
                        .put("delta", e.delta)
                        .put("at", e.createdAt)
                )
            }
        })
        root.put("stores", JSONArray(data.stores))
        root.put("prices", JSONArray().apply {
            data.prices.forEach { p ->
                put(
                    JSONObject()
                        .put("product", p.productUid)
                        .put("store", p.storeName)
                        .put("cents", p.priceCents)
                        .put("at", p.recordedAt)
                )
            }
        })
        return root.toString(1)
    }

    fun decode(text: String): SyncData {
        try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) {
                throw SyncFormatException("El fichero no es de sincronización de Despensa.")
            }
            if (root.optInt("version") > VERSION) {
                throw SyncFormatException("El fichero es de una versión más nueva de la app. Actualízala en este móvil.")
            }
            return SyncData(
                deviceName = root.optString("device", "otro móvil"),
                exportedAt = root.optLong("exportedAt"),
                products = root.getJSONArray("products").objects().map {
                    SyncProduct(
                        uid = it.getString("uid"),
                        name = it.getString("name"),
                        barcode = if (it.isNull("barcode")) null else it.getString("barcode"),
                        metaUpdatedAt = it.getLong("metaUpdatedAt"),
                    )
                },
                events = root.getJSONArray("events").objects().map {
                    SyncEvent(
                        uid = it.getString("uid"),
                        productUid = it.getString("product"),
                        delta = it.getInt("delta"),
                        createdAt = it.getLong("at"),
                    )
                },
                stores = root.getJSONArray("stores").let { array ->
                    (0 until array.length()).map { array.getString(it) }
                },
                prices = root.getJSONArray("prices").objects().map {
                    SyncPrice(
                        productUid = it.getString("product"),
                        storeName = it.getString("store"),
                        priceCents = it.getLong("cents"),
                        recordedAt = it.getLong("at"),
                    )
                },
            )
        } catch (e: JSONException) {
            throw SyncFormatException("El fichero está dañado o no es de sincronización de Despensa.")
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}

// ============================================================
//  Repositorio de sincronización
// ============================================================

class SyncRepository(
    private val dao: SyncDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Estado completo de este móvil como texto JSON. */
    suspend fun export(deviceName: String): String {
        val data = SyncData(
            deviceName = deviceName,
            exportedAt = clock(),
            products = dao.allProducts().map { SyncProduct(it.uid, it.name, it.barcode, it.metaUpdatedAt) },
            events = dao.allEvents(),
            stores = dao.allStoreNames(),
            prices = dao.allPrices(),
        )
        return SyncCodec.encode(data)
    }

    /** Lanza [SyncFormatException] si el texto no es un fichero válido. */
    suspend fun import(text: String): ImportSummary =
        dao.merge(SyncCodec.decode(text), clock())
}