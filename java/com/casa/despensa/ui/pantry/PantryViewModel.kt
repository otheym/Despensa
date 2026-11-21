package com.casa.despensa.ui.pantry




import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.casa.despensa.data.LookupResult
import com.casa.despensa.data.PantryRepository
import com.casa.despensa.data.Product
import com.casa.despensa.data.ScanResult
import com.casa.despensa.data.StockStatus
import com.casa.despensa.data.status
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------- Modelos de UI ----------

data class PantryItemUi(
    val id: Long,
    val name: String,
    val quantity: Int,
    val lastPriceCents: Long?,
    val status: StockStatus,
)

data class PantrySection(
    val status: StockStatus,
    val items: List<PantryItemUi>,
)

/** Alta de un código desconocido: se busca en línea mientras el diálogo ya está abierto. */
data class NewProductDraft(
    val barcode: String,
    val isLookingUp: Boolean = true,
    /** Nombre propuesto por la base de datos en línea (el usuario lo confirma o edita). */
    val suggestedName: String? = null,
    /** Base de datos que dio el nombre, p. ej. "Open Food Facts". */
    val source: String? = null,
    /** true si no se pudo consultar (sin conexión, timeout…). */
    val lookupFailed: Boolean = false,
)

/** Producto ya registrado cuyo código se acaba de escanear. */
data class ScannedProduct(
    val id: Long,
    val name: String,
    /** Cantidad en casa en el momento del escaneo. */
    val quantity: Int,
)

data class PantryUiState(
    val query: String = "",
    val sections: List<PantrySection> = emptyList(),
    val isLoading: Boolean = true,
    /** Código escaneado que no existe en la BD → la UI muestra el diálogo de alta. */
    val newProduct: NewProductDraft? = null,
    /** Producto en edición → la UI muestra el diálogo de edición. */
    val editing: PantryItemUi? = null,
    /** Producto escaneado → la UI muestra el diálogo para sumar o restar unidades. */
    val scanned: ScannedProduct? = null,
    /** Tiendas conocidas, para sugerirlas en los diálogos. */
    val storeNames: List<String> = emptyList(),
    /** Última tienda usada, para prerrellenar el campo. */
    val lastStoreName: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && sections.isEmpty()
}

sealed interface PantryEvent {
    data class ShowMessage(val text: String) : PantryEvent
}

// ---------- ViewModel ----------

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModel(
    private val repository: PantryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val newProduct = MutableStateFlow<NewProductDraft?>(null)
    private var lookupJob: Job? = null
    private val editing = MutableStateFlow<PantryItemUi?>(null)
    private val scanned = MutableStateFlow<ScannedProduct?>(null)

    private val _events = Channel<PantryEvent>(Channel.BUFFERED)
    /** Eventos de un solo uso (snackbars). */
    val events: Flow<PantryEvent> = _events.receiveAsFlow()

    private val products: Flow<List<Product>> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { repository.observePantry(it) }

    /** Tiendas + última usada en un solo flow (combine tipado admite como máximo 5 entradas). */
    private val storeInfo: Flow<Pair<List<String>, String?>> =
        combine(repository.observeStoreNames(), repository.observeLastStoreName()) { names, last ->
            names to last
        }

    /** Los tres diálogos en un solo flow, para no pasar de 5 entradas en el combine. */
    private val dialogs: Flow<Triple<NewProductDraft?, PantryItemUi?, ScannedProduct?>> =
        combine(newProduct, editing, scanned) { draft, edit, scan -> Triple(draft, edit, scan) }

    val uiState: StateFlow<PantryUiState> =
        combine(query, products, dialogs, storeInfo) { q, list, open, stores ->
            PantryUiState(
                query = q,
                sections = list.toSections(),
                isLoading = false,
                newProduct = open.first,
                editing = open.second,
                scanned = open.third,
                storeNames = stores.first,
                lastStoreName = stores.second,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PantryUiState())

    // ---------- Búsqueda y stock ----------

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onIncrement(id: Long) {
        viewModelScope.launch { repository.adjustQuantity(id, +1) }
    }

    fun onDecrement(id: Long) {
        viewModelScope.launch { repository.adjustQuantity(id, -1) }
    }

    /** Entrada por teclado desde el buscador. */
    fun onAddFromQuery() {
        val name = query.value.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val product = repository.addOrIncrementByName(name)
            query.value = ""
            message("${product.name}: ${product.quantity} en casa")
        }
    }

    // ---------- Escaneo y alta ----------

    /** Llamar con el rawValue devuelto por ML Kit. */
    fun onBarcodeScanned(rawValue: String?) {
        val code = rawValue?.trim().orEmpty()
        if (code.isEmpty()) return
        viewModelScope.launch {
            when (val result = repository.findScanned(code)) {
                is ScanResult.Found -> scanned.value = ScannedProduct(
                    id = result.product.id,
                    name = result.product.name,
                    quantity = result.product.quantity,
                )
                is ScanResult.Unknown ->
                    startNewProduct(result.barcode)
            }
        }
    }

    /**
     * Confirma la cantidad elegida tras escanear. Se aplica como movimiento (+n / −n) respecto
     * a la cantidad del momento del escaneo, así se suma bien con otros cambios y con la sincronización.
     */
    fun onConfirmScan(newQuantity: Int) {
        val item = scanned.value ?: return
        scanned.value = null
        val delta = newQuantity.coerceAtLeast(0) - item.quantity
        if (delta == 0) return
        viewModelScope.launch {
            repository.adjustQuantity(item.id, delta)
            val sign = if (delta > 0) "+" else "−"
            message("${item.name}: $sign${kotlin.math.abs(delta)} · ${newQuantity.coerceAtLeast(0)} en casa")
        }
    }

    fun onDismissScan() {
        scanned.value = null
    }

    /** Abre el diálogo de alta al momento y lanza la búsqueda en línea en paralelo. */
    private fun startNewProduct(barcode: String) {
        lookupJob?.cancel()
        newProduct.value = NewProductDraft(barcode = barcode)
        lookupJob = viewModelScope.launch {
            val result = repository.lookupOnline(barcode)
            newProduct.update { draft ->
                // Si el diálogo se cerró o es de otro código, no tocamos nada.
                if (draft == null || draft.barcode != barcode) {
                    draft
                } else {
                    when (result) {
                        is LookupResult.Found -> draft.copy(
                            isLookingUp = false,
                            suggestedName = result.product.suggestedName,
                            source = result.product.source,
                        )
                        LookupResult.NotFound -> draft.copy(isLookingUp = false)
                        LookupResult.Unavailable -> draft.copy(isLookingUp = false, lookupFailed = true)
                    }
                }
            }
        }
    }

    private fun closeNewProduct() {
        lookupJob?.cancel()
        lookupJob = null
        newProduct.value = null
    }

    fun onRegisterNewProduct(name: String, priceText: String, storeText: String, quantity: Int) {
        val barcode = newProduct.value?.barcode ?: return
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val priceCents = parsePriceToCents(priceText)
        val store = storeText.trim()
        if (priceText.isNotBlank() && priceCents == null) return        // precio inválido
        if (priceCents != null && store.isEmpty()) return               // falta la tienda
        viewModelScope.launch {
            try {
                repository.createProduct(
                    name = cleanName,
                    barcode = barcode,
                    priceCents = priceCents,
                    storeName = store.ifEmpty { null },
                    initialQuantity = quantity.coerceAtLeast(0),
                )
                closeNewProduct()
                message("$cleanName añadido a la despensa")
            } catch (e: SQLiteConstraintException) {
                closeNewProduct()
                message("Ese código ya está registrado")
            }
        }
    }

    fun onDismissNewProduct() {
        closeNewProduct()
    }

    // ---------- Edición ----------

    fun onEditClick(item: PantryItemUi) {
        editing.value = item
    }

    fun onDismissEdit() {
        editing.value = null
    }

    fun onSaveEdit(name: String, priceText: String, storeText: String) {
        val item = editing.value ?: return
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val newPrice = parsePriceToCents(priceText)
        if (priceText.isNotBlank() && newPrice == null) return          // precio inválido
        val priceChanged = newPrice != item.lastPriceCents
        val store = storeText.trim()
        if (priceChanged && newPrice != null && store.isEmpty()) return // falta la tienda

        viewModelScope.launch {
            repository.updateProduct(
                id = item.id,
                name = cleanName,
                newPriceCents = newPrice,
                priceChanged = priceChanged,
                storeName = store.ifEmpty { null },
            )
            editing.value = null
            message(
                if (priceChanged && newPrice != null) "$cleanName: precio guardado en el historial ($store)"
                else "$cleanName actualizado"
            )
        }
    }

    fun onDeleteEdit() {
        val item = editing.value ?: return
        viewModelScope.launch {
            repository.deleteProduct(item.id)
            editing.value = null
            message("${item.name} eliminado de la despensa")
        }
    }

    private suspend fun message(text: String) = _events.send(PantryEvent.ShowMessage(text))

    companion object {
        fun factory(repository: PantryRepository) = viewModelFactory {
            initializer { PantryViewModel(repository) }
        }
    }
}

// ---------- Lógica pura (fácil de testear con JUnit) ----------

private val pantryOrder = compareBy<PantryItemUi>(
    { it.status.ordinal },       // Rojo → Amarillo → Verde
    { it.quantity },             // dentro del mismo color, menos cantidad primero
    { it.name.lowercase() },
)

internal fun List<Product>.toSections(): List<PantrySection> {
    val grouped = this
        .map { PantryItemUi(it.id, it.name, it.quantity, it.lastPriceCents, it.status) }
        .sortedWith(pantryOrder)
        .groupBy { it.status }
    return StockStatus.entries.mapNotNull { status ->
        grouped[status]?.let { PantrySection(status, it) }
    }
}

/** "1,25" / "1.25" / "1,25 €" → 125. Vacío o inválido → null. */
internal fun parsePriceToCents(text: String): Long? =
    text.replace("€", "")
        .trim()
        .replace(',', '.')
        .toBigDecimalOrNull()
        ?.takeIf { it.signum() >= 0 }
        ?.setScale(2, RoundingMode.HALF_UP)
        ?.movePointRight(2)
        ?.toLong()

/** 125 → "1,25" */
internal fun centsToText(cents: Long): String =
    BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')