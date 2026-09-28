package com.casa.despensa.ui.prices



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.casa.despensa.data.PantryRepository
import com.casa.despensa.data.PricePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// ---------- Modelos de UI (sin Compose: el color se resuelve en la pantalla) ----------

data class ProductOption(val id: Long, val name: String)

data class ChartPoint(
    val recordedAt: Long,
    val priceCents: Long,
    val storeName: String,
    /** Índice estable de color de la tienda (la pantalla lo traduce a un Color de la paleta). */
    val colorIndex: Int,
)

data class StoreLegendEntry(val name: String, val colorIndex: Int)

data class PriceSummary(
    val lastCents: Long,
    val minCents: Long,
    val maxCents: Long,
    /** Variación del último precio respecto al primero, en %. Null si solo hay un punto. */
    val changePercent: Double?,
)

data class PriceChartUiState(
    val isLoading: Boolean = true,
    val products: List<ProductOption> = emptyList(),
    val selected: ProductOption? = null,
    /** Ordenados del más antiguo al más reciente. */
    val points: List<ChartPoint> = emptyList(),
    /** Solo las tiendas que aparecen en la gráfica del producto elegido. */
    val legend: List<StoreLegendEntry> = emptyList(),
    val summary: PriceSummary? = null,
)

// ---------- ViewModel ----------

@OptIn(ExperimentalCoroutinesApi::class)
class PriceChartViewModel(
    private val repository: PantryRepository,
) : ViewModel() {

    private val selectedId = MutableStateFlow<Long?>(null)

    private val products: Flow<List<ProductOption>> =
        repository.observeProductsWithHistory()
            .map { list -> list.map { ProductOption(it.id, it.name) } }

    /** El elegido; si no hay ninguno (o ya no existe), el primero de la lista. */
    private val selected: Flow<ProductOption?> =
        combine(products, selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.distinctUntilChanged()

    private val history: Flow<List<PricePoint>> =
        selected
            .map { it?.id }
            .distinctUntilChanged()
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else repository.observePriceHistory(id)
            }

    /** nombre de tienda → índice de color, basado en su id para que no cambie entre productos. */
    private val storeColors: Flow<Map<String, Int>> =
        repository.observeStores().map { stores ->
            stores.associate { it.name to (it.id - 1).toInt() }
        }

    val uiState: StateFlow<PriceChartUiState> =
        combine(products, selected, history, storeColors) { list, sel, records, colors ->
            val points = records.map { r ->
                ChartPoint(
                    recordedAt = r.recordedAt,
                    priceCents = r.priceCents,
                    storeName = r.storeName,
                    colorIndex = colors[r.storeName] ?: 0,
                )
            }
            PriceChartUiState(
                isLoading = false,
                products = list,
                selected = sel,
                points = points,
                legend = points
                    .distinctBy { it.storeName }
                    .map { StoreLegendEntry(it.storeName, it.colorIndex) }
                    .sortedBy { it.name.lowercase() },
                summary = points.toSummary(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PriceChartUiState())

    fun onSelectProduct(id: Long) {
        selectedId.value = id
    }

    companion object {
        fun factory(repository: PantryRepository) = viewModelFactory {
            initializer { PriceChartViewModel(repository) }
        }
    }
}

internal fun List<ChartPoint>.toSummary(): PriceSummary? {
    if (isEmpty()) return null
    val first = first().priceCents
    val last = last().priceCents
    return PriceSummary(
        lastCents = last,
        minCents = minOf { it.priceCents },
        maxCents = maxOf { it.priceCents },
        changePercent = if (size > 1 && first > 0) (last - first) * 100.0 / first else null,
    )
}