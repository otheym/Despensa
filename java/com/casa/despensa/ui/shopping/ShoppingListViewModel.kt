package com.casa.despensa.ui.shopping



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.casa.despensa.data.PantryRepository
import com.casa.despensa.data.StockStatus
import com.casa.despensa.data.status
import com.casa.despensa.ui.pantry.parsePriceToCents
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------- Modelos de UI ----------

data class ShoppingItemUi(
    val id: Long,
    val name: String,
    val quantity: Int,
    val lastPriceCents: Long?,
    val status: StockStatus,
)

data class ShoppingListUiState(
    val isLoading: Boolean = true,
    val includeLowStock: Boolean = false,
    /** Rojos primero, luego amarillos (orden por cantidad ascendente). */
    val items: List<ShoppingItemUi> = emptyList(),
    /** Producto cuyo diálogo "Comprado" (unidades, precio y tienda) está abierto. */
    val purchasing: ShoppingItemUi? = null,
    val storeNames: List<String> = emptyList(),
    val lastStoreName: String? = null,
) {
    val outOfStockCount: Int get() = items.count { it.status == StockStatus.RED }
    val estimatedCostCents: Long? get() = items.mapNotNull { it.lastPriceCents }.takeIf { it.isNotEmpty() }?.sum()
}

sealed interface ShoppingEvent {
    data class Message(val text: String) : ShoppingEvent
    /** Compra rápida (casilla): la pantalla ofrece "Deshacer". */
    data class Purchased(val name: String, val productId: Long, val previousQuantity: Int) : ShoppingEvent
}

// ---------- ViewModel ----------

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingListViewModel(
    private val repository: PantryRepository,
) : ViewModel() {

    private val includeLowStock = MutableStateFlow(false)
    private val purchasing = MutableStateFlow<ShoppingItemUi?>(null)

    private val _events = Channel<ShoppingEvent>(Channel.BUFFERED)
    val events: Flow<ShoppingEvent> = _events.receiveAsFlow()

    private val items: Flow<List<ShoppingItemUi>> =
        includeLowStock.flatMapLatest { include ->
            repository.observeShoppingList(include)
        }.map { list ->
            list.map { ShoppingItemUi(it.id, it.name, it.quantity, it.lastPriceCents, it.status) }
        }

    private val storeInfo: Flow<Pair<List<String>, String?>> =
        combine(repository.observeStoreNames(), repository.observeLastStoreName()) { names, last ->
            names to last
        }

    val uiState: StateFlow<ShoppingListUiState> =
        combine(includeLowStock, items, purchasing, storeInfo) { include, list, buying, stores ->
            ShoppingListUiState(
                isLoading = false,
                includeLowStock = include,
                items = list,
                purchasing = buying,
                storeNames = stores.first,
                lastStoreName = stores.second,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingListUiState())

    fun onIncludeLowStockChange(include: Boolean) {
        includeLowStock.value = include
    }

    /** Casilla marcada: comprado sin anotar precio → pasa a verde. Se puede deshacer. */
    fun onQuickPurchase(item: ShoppingItemUi) {
        viewModelScope.launch {
            repository.markAsPurchased(item.id)
            _events.send(ShoppingEvent.Purchased(item.name, item.id, item.quantity))
        }
    }

    fun onUndoPurchase(productId: Long, previousQuantity: Int) {
        viewModelScope.launch { repository.setQuantity(productId, previousQuantity) }
    }

    fun onOpenPurchase(item: ShoppingItemUi) {
        purchasing.value = item
    }

    fun onDismissPurchase() {
        purchasing.value = null
    }

    /** Diálogo "Comprado": unidades + precio y tienda opcionales (el precio va al historial). */
    fun onConfirmPurchase(units: Int, priceText: String, storeText: String) {
        val item = purchasing.value ?: return
        val priceCents = parsePriceToCents(priceText)
        val store = storeText.trim()
        if (priceText.isNotBlank() && priceCents == null) return
        if (priceCents != null && store.isEmpty()) return
        viewModelScope.launch {
            repository.markAsPurchased(
                id = item.id,
                bought = units.coerceAtLeast(1),
                priceCents = priceCents,
                storeName = store.ifEmpty { null },
            )
            purchasing.value = null
            _events.send(
                ShoppingEvent.Message(
                    if (priceCents != null) "${item.name}: comprado en $store" else "${item.name}: comprado"
                )
            )
        }
    }

    companion object {
        fun factory(repository: PantryRepository) = viewModelFactory {
            initializer { ShoppingListViewModel(repository) }
        }
    }
}

// ---------- Texto para compartir (lógica pura) ----------

private val spanish: Locale = Locale.forLanguageTag("es-ES")

/**
 * Texto con formato de WhatsApp (*negrita*). Ejemplo:
 *
 * 🛒 *Lista de la compra* · 26/09/2026
 *
 * *Agotado*
 * • Leche entera
 *
 * *Queda poco*
 * • Aceite de oliva (quedan 2)
 *
 * Coste estimado: 12,40 €
 */
internal fun buildShoppingListText(
    items: List<ShoppingItemUi>,
    now: Long = System.currentTimeMillis(),
): String {
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy", spanish))
    val euros = NumberFormat.getCurrencyInstance(spanish)
    val outOfStock = items.filter { it.status == StockStatus.RED }
    val lowStock = items.filter { it.status == StockStatus.YELLOW }

    return buildString {
        appendLine("🛒 *Lista de la compra* · $date")
        if (outOfStock.isNotEmpty()) {
            appendLine()
            appendLine("*Agotado*")
            outOfStock.forEach { appendLine("• ${it.name}") }
        }
        if (lowStock.isNotEmpty()) {
            appendLine()
            appendLine("*Queda poco*")
            lowStock.forEach {
                val left = if (it.quantity == 1) "queda 1" else "quedan ${it.quantity}"
                appendLine("• ${it.name} ($left)")
            }
        }
        val prices = items.mapNotNull { it.lastPriceCents }
        if (prices.isNotEmpty()) {
            appendLine()
            append("Coste estimado: ${euros.format(prices.sum() / 100.0)}")
            val withoutPrice = items.size - prices.size
            if (withoutPrice > 0) {
                append(if (withoutPrice == 1) " (1 producto sin precio)" else " ($withoutPrice productos sin precio)")
            }
        }
    }.trimEnd()
}