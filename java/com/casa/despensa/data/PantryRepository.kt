package com.casa.despensa.data


import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface ScanResult {
    data class Incremented(val product: Product) : ScanResult
    data class Unknown(val barcode: String) : ScanResult
}

/**
 * Punto único de acceso a los datos. Regla de precios: cualquier precio nuevo pasa por
 * PriceDao.recordPrice, que guarda tienda + fecha en el historial y actualiza el último precio.
 */
class PantryRepository(
    private val dao: ProductDao,
    private val priceDao: PriceDao,
    private val lookup: ProductLookup,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ---------- Lecturas ----------

    fun observePantry(query: String): Flow<List<Product>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query.trim())

    fun observeShoppingList(includeLowStock: Boolean): Flow<List<Product>> =
        dao.observeShoppingList(if (includeLowStock) StockStatus.LOW_STOCK_MAX else 0)

    fun observeStoreNames(): Flow<List<String>> =
        priceDao.observeStores().map { stores -> stores.map { it.name } }

    fun observeLastStoreName(): Flow<String?> = priceDao.observeLastStoreName()

    /** Tiendas con su id: el id da a cada tienda un color estable en las gráficas. */
    fun observeStores(): Flow<List<Store>> = priceDao.observeStores()

    /** Para la futura pestaña de gráficas. */
    fun observePriceHistory(productId: Long): Flow<List<PricePoint>> =
        priceDao.observeHistory(productId)

    /** Para el selector de producto de la pestaña de gráficas. */
    fun observeProductsWithHistory(): Flow<List<Product>> =
        priceDao.observeProductsWithHistory()

    // ---------- Stock ----------

    suspend fun adjustQuantity(id: Long, delta: Int) {
        dao.changeQuantity(id, delta, clock())
    }

    /** Fija la cantidad exacta (p. ej. para deshacer una compra). */
    suspend fun setQuantity(id: Long, quantity: Int) {
        dao.setQuantity(id, quantity, clock())
    }

    suspend fun registerScan(barcode: String): ScanResult =
        dao.incrementByBarcode(barcode, clock())
            ?.let { ScanResult.Incremented(it) }
            ?: ScanResult.Unknown(barcode)

    // ---------- Búsqueda en línea ----------

    /** Busca el código en las bases de datos abiertas (Open Food Facts y hermanas). */
    suspend fun lookupOnline(barcode: String): LookupResult = lookup.lookup(barcode)

    // ---------- Alta y edición ----------

    /**
     * Crea un producto. Si llega precio, se registra como primer punto del historial,
     * para lo que la tienda es obligatoria. Lanza SQLiteConstraintException si el código ya existe.
     */
    suspend fun createProduct(
        name: String,
        barcode: String?,
        priceCents: Long?,
        storeName: String?,
        initialQuantity: Int,
    ): Long {
        val now = clock()
        val id = dao.insertWithStock(
            Product(
                name = name.trim(),
                barcode = barcode?.takeIf { it.isNotBlank() },
                lastPriceCents = null,
                quantity = initialQuantity.coerceAtLeast(0),
                updatedAt = now,
                metaUpdatedAt = now,
            ),
            now,
        )
        if (priceCents != null && !storeName.isNullOrBlank()) {
            priceDao.recordPrice(id, priceCents, storeName, now)
        }
        return id
    }

    /** Entrada por teclado: si ya existe un producto con ese nombre, suma 1; si no, lo crea. */
    suspend fun addOrIncrementByName(name: String): Product {
        val existing = dao.findByName(name.trim())
        return if (existing != null) {
            dao.changeQuantity(existing.id, +1, clock())
            existing.copy(quantity = existing.quantity + 1)
        } else {
            val id = createProduct(name, barcode = null, priceCents = null, storeName = null, initialQuantity = 1)
            Product(id = id, name = name.trim(), quantity = 1)
        }
    }

    /**
     * Edición desde el diálogo.
     * - Siempre actualiza el nombre.
     * - Si el precio no cambió, el historial no se toca.
     * - Si cambió a un valor: nuevo punto en el historial con tienda y fecha (tienda obligatoria).
     * - Si se borró el precio: el producto queda sin precio; el historial se conserva.
     */
    suspend fun updateProduct(
        id: Long,
        name: String,
        newPriceCents: Long?,
        priceChanged: Boolean,
        storeName: String?,
    ) {
        val now = clock()
        dao.updateName(id, name.trim(), now)
        if (!priceChanged) return
        if (newPriceCents == null) {
            priceDao.setProductLastPrice(id, null, now)
        } else {
            val store = requireNotNull(storeName?.takeIf { it.isNotBlank() }) {
                "El lugar de compra es obligatorio al cambiar el precio"
            }
            priceDao.recordPrice(id, newPriceCents, store, now)
        }
    }

    /** Lista de la compra / OCR: "comprado" → al menos GREEN_MIN unidades, y precio al historial si lo hay. */
    suspend fun markAsPurchased(
        id: Long,
        bought: Int = 1,
        priceCents: Long? = null,
        storeName: String? = null,
    ) {
        val now = clock()
        dao.registerPurchase(id, bought, StockStatus.GREEN_MIN, now)
        if (priceCents != null && !storeName.isNullOrBlank()) {
            priceDao.recordPrice(id, priceCents, storeName, now)
        }
    }
}