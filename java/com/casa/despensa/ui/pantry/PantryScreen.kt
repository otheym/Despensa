package com.casa.despensa.ui.pantry


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casa.despensa.data.StockStatus
import java.text.NumberFormat
import java.util.Locale

// ============================================================
//  Route: conecta ViewModel ↔ UI (la pantalla en sí es stateless)
// ============================================================

@Composable
fun PantryRoute(
    viewModel: PantryViewModel,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PantryEvent.ShowMessage -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    PantryScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onAddFromQuery = viewModel::onAddFromQuery,
        onIncrement = viewModel::onIncrement,
        onDecrement = viewModel::onDecrement,
        onScanClick = onScanClick,
        onConfirmNewProduct = viewModel::onRegisterNewProduct,
        onDismissNewProduct = viewModel::onDismissNewProduct,
        onEditClick = viewModel::onEditClick,
        onSaveEdit = viewModel::onSaveEdit,
        onDismissEdit = viewModel::onDismissEdit,
        modifier = modifier,
    )
}

// ============================================================
//  Semáforo: colores y textos (capa de UI, no de datos)
// ============================================================

private val StockRed = Color(0xFFD32F2F)
private val StockAmber = Color(0xFFF2A900)
private val StockGreen = Color(0xFF2E7D32)

private fun StockStatus.color(): Color = when (this) {
    StockStatus.RED -> StockRed
    StockStatus.YELLOW -> StockAmber
    StockStatus.GREEN -> StockGreen
}

private fun StockStatus.label(): String = when (this) {
    StockStatus.RED -> "Agotado"
    StockStatus.YELLOW -> "Queda poco"
    StockStatus.GREEN -> "Stock suficiente"
}

private val euroFormat: NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"))

private fun formatPrice(cents: Long?): String =
    cents?.let { "Última compra: ${euroFormat.format(it / 100.0)}" }
        ?: "Sin precio · toca para añadirlo"

// ============================================================
//  Pantalla
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(
    state: PantryUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onAddFromQuery: () -> Unit,
    onIncrement: (Long) -> Unit,
    onDecrement: (Long) -> Unit,
    onScanClick: () -> Unit,
    onConfirmNewProduct: (name: String, price: String, store: String) -> Unit,
    onDismissNewProduct: () -> Unit,
    onEditClick: (PantryItemUi) -> Unit,
    onSaveEdit: (name: String, price: String, store: String) -> Unit,
    onDismissEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("La despensa") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Escanear código") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                onSubmit = onAddFromQuery,
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.isEmpty -> EmptyState(query = state.query, onAddFromQuery = onAddFromQuery)
                else -> PantryList(
                    sections = state.sections,
                    onEditClick = onEditClick,
                    onIncrement = onIncrement,
                    onDecrement = onDecrement,
                )
            }
        }
    }

    state.newProduct?.let { draft ->
        NewProductDialog(
            draft = draft,
            knownStores = state.storeNames,
            lastStoreName = state.lastStoreName,
            onConfirm = onConfirmNewProduct,
            onDismiss = onDismissNewProduct,
        )
    }

    state.editing?.let { item ->
        EditProductDialog(
            item = item,
            knownStores = state.storeNames,
            lastStoreName = state.lastStoreName,
            onSave = onSaveEdit,
            onDismiss = onDismissEdit,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Buscar o añadir producto") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Borrar búsqueda")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PantryList(
    sections: List<PantrySection>,
    onEditClick: (PantryItemUi) -> Unit,
    onIncrement: (Long) -> Unit,
    onDecrement: (Long) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            stickyHeader(key = "header_${section.status.name}") {
                SectionHeader(status = section.status, count = section.items.size)
            }
            items(items = section.items, key = { it.id }) { item ->
                ProductRow(
                    item = item,
                    onClick = { onEditClick(item) },
                    onIncrement = { onIncrement(item.id) },
                    onDecrement = { onDecrement(item.id) },
                    // Misma key entre secciones → el ítem "viaja" animado de Rojo a Amarillo, etc.
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(status: StockStatus, count: Int) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(status.color(), CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${status.label()} ($count)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProductRow(
    item: PantryItemUi,
    onClick: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = item.status.color()
    val container = statusColor.copy(alpha = 0.08f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Barra lateral del semáforo
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(statusColor),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatPrice(item.lastPriceCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Edit, contentDescription = "Editar ${item.name}")
            }
            QuantityStepper(
                name = item.name,
                quantity = item.quantity,
                onDecrement = onDecrement,
                onIncrement = onIncrement,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun QuantityStepper(
    name: String,
    quantity: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onDecrement,
            enabled = quantity > 0,
            modifier = Modifier.semantics { contentDescription = "Quitar una unidad de $name" },
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        Text(
            text = quantity.toString(),
            modifier = Modifier.widthIn(min = 36.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        FilledTonalIconButton(
            onClick = onIncrement,
            modifier = Modifier.semantics { contentDescription = "Añadir una unidad de $name" },
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun EmptyState(query: String, onAddFromQuery: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (query.isBlank()) {
            Text(
                "La despensa está vacía. Escanea un código o escribe el nombre de un producto.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                "No hay ningún producto llamado «${query.trim()}».",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAddFromQuery) {
                Text("Añadir «${query.trim()}»")
            }
        }
    }
}

@Composable
private fun NewProductDialog(
    draft: NewProductDraft,
    knownStores: List<String>,
    lastStoreName: String?,
    onConfirm: (name: String, price: String, store: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(draft.barcode) { mutableStateOf("") }
    var nameEditedByUser by rememberSaveable(draft.barcode) { mutableStateOf(false) }
    var price by rememberSaveable(draft.barcode) { mutableStateOf("") }
    var store by rememberSaveable(draft.barcode) { mutableStateOf(lastStoreName.orEmpty()) }

    // Cuando llega la sugerencia en línea, rellena el nombre salvo que ya hayas escrito algo.
    LaunchedEffect(draft.suggestedName) {
        val suggestion = draft.suggestedName
        if (suggestion != null && !nameEditedByUser) name = suggestion
    }

    val parsed = parsePriceToCents(price)
    val priceValid = price.isBlank() || parsed != null
    val storeRequired = parsed != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Producto nuevo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Código ${draft.barcode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LookupStatus(draft)
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameEditedByUser = true
                    },
                    label = { Text("Nombre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                PriceField(value = price, onValueChange = { price = it }, isValid = priceValid, label = "Precio (opcional)")
                if (storeRequired) {
                    StoreField(value = store, onValueChange = { store = it }, knownStores = knownStores)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, price, store) },
                enabled = name.isNotBlank() && priceValid && (!storeRequired || store.isNotBlank()),
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

/** Línea de estado de la búsqueda en línea dentro del diálogo de alta. */
@Composable
private fun LookupStatus(draft: NewProductDraft) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        draft.isLookingUp -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Buscando el producto en línea…", style = style, color = color)
        }
        draft.suggestedName != null ->
            Text("Nombre sugerido por ${draft.source}. Revísalo antes de guardar.", style = style, color = color)
        draft.lookupFailed ->
            Text("No se pudo consultar en línea. Escribe el nombre.", style = style, color = color)
        else ->
            Text("No aparece en las bases de datos abiertas. Escribe el nombre.", style = style, color = color)
    }
}

@Composable
private fun EditProductDialog(
    item: PantryItemUi,
    knownStores: List<String>,
    lastStoreName: String?,
    onSave: (name: String, price: String, store: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var price by rememberSaveable(item.id) {
        mutableStateOf(item.lastPriceCents?.let(::centsToText).orEmpty())
    }
    var store by rememberSaveable(item.id) { mutableStateOf(lastStoreName.orEmpty()) }

    val parsed = parsePriceToCents(price)
    val priceValid = price.isBlank() || parsed != null
    // La tienda solo se pide si el precio cambia a un valor nuevo (eso crea un punto en el historial).
    val storeRequired = priceValid && parsed != null && parsed != item.lastPriceCents

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar producto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                PriceField(value = price, onValueChange = { price = it }, isValid = priceValid, label = "Precio de la última compra")
                if (storeRequired) {
                    StoreField(value = store, onValueChange = { store = it }, knownStores = knownStores)
                    Text(
                        "Se guardará en el historial con la fecha de hoy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, price, store) },
                enabled = name.isNotBlank() && priceValid && (!storeRequired || store.isNotBlank()),
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
internal fun PriceField(
    value: String,
    onValueChange: (String) -> Unit,
    isValid: Boolean,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text("€") },
        singleLine = true,
        isError = !isValid,
        supportingText = if (!isValid) {
            { Text("Escribe un número, por ejemplo 1,25") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
}

/** Campo de tienda con sugerencias de las tiendas ya usadas. */
@Composable
internal fun StoreField(
    value: String,
    onValueChange: (String) -> Unit,
    knownStores: List<String>,
) {
    val typed = value.trim()
    val suggestions = remember(typed, knownStores) {
        knownStores
            .filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
            .take(6)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Lugar de compra") },
            placeholder = { Text("Ej.: Mercadona") },
            singleLine = true,
            isError = typed.isEmpty(),
            supportingText = if (typed.isEmpty()) {
                { Text("Obligatorio al registrar un precio") }
            } else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        if (suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { suggestion ->
                    SuggestionChip(
                        onClick = { onValueChange(suggestion) },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
    }
}

// ============================================================
//  Preview
// ============================================================

@Preview(showBackground = true)
@Composable
private fun PantryScreenPreview() {
    val items = listOf(
        PantryItemUi(1, "Leche entera", 0, 105, StockStatus.RED),
        PantryItemUi(2, "Huevos (docena)", 1, 289, StockStatus.YELLOW),
        PantryItemUi(3, "Aceite de oliva", 2, 899, StockStatus.YELLOW),
        PantryItemUi(4, "Arroz", 5, 129, StockStatus.GREEN),
        PantryItemUi(5, "Garbanzos", 4, null, StockStatus.GREEN),
    )
    MaterialTheme {
        PantryScreen(
            state = PantryUiState(
                sections = items.groupBy { it.status }.map { (s, l) -> PantrySection(s, l) },
                isLoading = false,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onQueryChange = {}, onAddFromQuery = {}, onIncrement = {}, onDecrement = {},
            onScanClick = {}, onConfirmNewProduct = { _, _, _ -> }, onDismissNewProduct = {},
            onEditClick = {}, onSaveEdit = { _, _, _ -> }, onDismissEdit = {},
        )
    }
}