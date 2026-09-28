package com.casa.despensa.ui.shopping



import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casa.despensa.data.StockStatus
import com.casa.despensa.ui.pantry.PriceField
import com.casa.despensa.ui.pantry.StoreField
import com.casa.despensa.ui.pantry.centsToText
import com.casa.despensa.ui.pantry.parsePriceToCents
import java.text.NumberFormat
import java.util.Locale

// ============================================================
//  Route
// ============================================================

@Composable
fun ShoppingListRoute(
    viewModel: ShoppingListViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShoppingEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is ShoppingEvent.Purchased -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "${event.name}: comprado",
                        actionLabel = "Deshacer",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.onUndoPurchase(event.productId, event.previousQuantity)
                    }
                }
            }
        }
    }

    ShoppingListScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onIncludeLowStockChange = viewModel::onIncludeLowStockChange,
        onQuickPurchase = viewModel::onQuickPurchase,
        onOpenPurchase = viewModel::onOpenPurchase,
        onConfirmPurchase = viewModel::onConfirmPurchase,
        onDismissPurchase = viewModel::onDismissPurchase,
        onSendWhatsApp = { sendToWhatsApp(context, buildShoppingListText(state.items)) },
        onShare = { shareWithChooser(context, buildShoppingListText(state.items)) },
        modifier = modifier,
    )
}

// ============================================================
//  Compartir
// ============================================================

private fun textIntent(text: String) = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, text)
}

/**
 * Abre WhatsApp directamente con el texto listo para elegir el chat.
 * Prueba WhatsApp normal y WhatsApp Business; si no hay ninguno, abre el menú de compartir.
 * No necesita permisos ni declarar <queries>: startActivity con setPackage falla con una excepción
 * si la app no está instalada.
 */
internal fun sendToWhatsApp(context: Context, text: String) {
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            context.startActivity(textIntent(text).setPackage(pkg))
            return
        } catch (e: ActivityNotFoundException) {
            // Probar el siguiente
        }
    }
    Toast.makeText(context, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
    shareWithChooser(context, text)
}

/** Menú de compartir del sistema (Telegram, email, notas…). */
internal fun shareWithChooser(context: Context, text: String) {
    context.startActivity(Intent.createChooser(textIntent(text), "Enviar lista de la compra"))
}

// ============================================================
//  Pantalla
// ============================================================

private val StockRed = Color(0xFFD32F2F)
private val StockAmber = Color(0xFFF2A900)
private val euroFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"))
private fun euros(cents: Long) = euroFormat.format(cents / 100.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    state: ShoppingListUiState,
    snackbarHostState: SnackbarHostState,
    onIncludeLowStockChange: (Boolean) -> Unit,
    onQuickPurchase: (ShoppingItemUi) -> Unit,
    onOpenPurchase: (ShoppingItemUi) -> Unit,
    onConfirmPurchase: (units: Int, price: String, store: String) -> Unit,
    onDismissPurchase: () -> Unit,
    onSendWhatsApp: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasItems = state.items.isNotEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Lista de la compra") },
                actions = {
                    if (hasItems) {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, contentDescription = "Compartir con otra app")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasItems) {
                ExtendedFloatingActionButton(
                    onClick = onSendWhatsApp,
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    text = { Text("Enviar por WhatsApp") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterChip(
                selected = state.includeLowStock,
                onClick = { onIncludeLowStockChange(!state.includeLowStock) },
                label = { Text("Incluir lo que queda poco") },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !hasItems -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.includeLowStock) "No falta nada: todo tiene stock suficiente."
                        else "No hay nada agotado. Activa «Incluir lo que queda poco» para adelantarte.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                ) {
                    item {
                        Text(
                            "Marca la casilla al comprarlo, o toca el producto para anotar unidades, precio y tienda.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(state.items, key = { it.id }) { item ->
                        ShoppingRow(
                            item = item,
                            onCheck = { onQuickPurchase(item) },
                            onClick = { onOpenPurchase(item) },
                            modifier = Modifier.animateItem(),
                        )
                        HorizontalDivider()
                    }
                    state.estimatedCostCents?.let { total ->
                        item {
                            Text(
                                "Coste estimado: ${euros(total)}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    state.purchasing?.let { item ->
        PurchaseDialog(
            item = item,
            knownStores = state.storeNames,
            lastStoreName = state.lastStoreName,
            onConfirm = onConfirmPurchase,
            onDismiss = onDismissPurchase,
        )
    }
}

@Composable
private fun ShoppingRow(
    item: ShoppingItemUi,
    onCheck: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = if (item.status == StockStatus.RED) StockRed else StockAmber
    val statusText = when {
        item.status == StockStatus.RED -> "Agotado"
        item.quantity == 1 -> "Queda 1"
        else -> "Quedan ${item.quantity}"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Anotar compra", onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = false,
            onCheckedChange = { onCheck() },
            modifier = Modifier.semantics { contentDescription = "Marcar ${item.name} como comprado" },
        )
        Box(Modifier.size(10.dp).background(statusColor, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                statusText + (item.lastPriceCents?.let { " · ${euros(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PurchaseDialog(
    item: ShoppingItemUi,
    knownStores: List<String>,
    lastStoreName: String?,
    onConfirm: (units: Int, price: String, store: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var units by rememberSaveable(item.id) { mutableIntStateOf(1) }
    var price by rememberSaveable(item.id) { mutableStateOf(item.lastPriceCents?.let(::centsToText).orEmpty()) }
    var store by rememberSaveable(item.id) { mutableStateOf(lastStoreName.orEmpty()) }

    val parsed = parsePriceToCents(price)
    val priceValid = price.isBlank() || parsed != null
    val storeRequired = parsed != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comprado: ${item.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unidades", modifier = Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = { if (units > 1) units-- },
                        enabled = units > 1,
                        modifier = Modifier.semantics { contentDescription = "Una unidad menos" },
                    ) { Text("−", modifier = Modifier.clearAndSetSemantics {}) }
                    Text(
                        units.toString(),
                        modifier = Modifier.widthIn(min = 36.dp),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                    )
                    FilledTonalIconButton(
                        onClick = { if (units < 99) units++ },
                        modifier = Modifier.semantics { contentDescription = "Una unidad más" },
                    ) { Text("+", modifier = Modifier.clearAndSetSemantics {}) }
                }
                PriceField(value = price, onValueChange = { price = it }, isValid = priceValid, label = "Precio por unidad (opcional)")
                if (storeRequired) {
                    StoreField(value = store, onValueChange = { store = it }, knownStores = knownStores)
                    Text(
                        "El precio se guardará en el historial con la fecha de hoy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(units, price, store) },
                enabled = priceValid && (!storeRequired || store.isNotBlank()),
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

// ============================================================
//  Preview
// ============================================================

@Preview(showBackground = true)
@Composable
private fun ShoppingListPreview() {
    MaterialTheme {
        ShoppingListScreen(
            state = ShoppingListUiState(
                isLoading = false,
                includeLowStock = true,
                items = listOf(
                    ShoppingItemUi(1, "Leche entera", 0, 105, StockStatus.RED),
                    ShoppingItemUi(2, "Pan de molde", 0, null, StockStatus.RED),
                    ShoppingItemUi(3, "Huevos (docena)", 1, 289, StockStatus.YELLOW),
                    ShoppingItemUi(4, "Aceite de oliva", 2, 899, StockStatus.YELLOW),
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onIncludeLowStockChange = {}, onQuickPurchase = {}, onOpenPurchase = {},
            onConfirmPurchase = { _, _, _ -> }, onDismissPurchase = {},
            onSendWhatsApp = {}, onShare = {},
        )
    }
}