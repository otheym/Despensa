package com.casa.despensa.ui.prices



import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// ============================================================
//  Route
// ============================================================

@Composable
fun PriceChartRoute(
    viewModel: PriceChartViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PriceChartScreen(state = state, onSelectProduct = viewModel::onSelectProduct, modifier = modifier)
}

// ============================================================
//  Paleta de tiendas y formatos
// ============================================================

/** Paleta categórica (Tableau 10): colores fáciles de distinguir entre sí. */
private val StorePalette = listOf(
    Color(0xFF4E79A7), // azul
    Color(0xFFF28E2B), // naranja
    Color(0xFFE15759), // rojo
    Color(0xFF76B7B2), // turquesa
    Color(0xFF59A14F), // verde
    Color(0xFFEDC948), // amarillo
    Color(0xFFB07AA1), // morado
    Color(0xFFFF9DA7), // rosa
    Color(0xFF9C755F), // marrón
    Color(0xFF7F7F7F), // gris
)

internal fun storeColor(index: Int): Color = StorePalette[index.mod(StorePalette.size)]

private val spanish: Locale = Locale.forLanguageTag("es-ES")
private val euroFormat: NumberFormat = NumberFormat.getCurrencyInstance(spanish)
private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy", spanish)
private val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", spanish)

private fun euros(cents: Long): String = euroFormat.format(cents / 100.0)
private fun formatDate(millis: Long, formatter: DateTimeFormatter): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)

// ============================================================
//  Pantalla
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceChartScreen(
    state: PriceChartUiState,
    onSelectProduct: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Precios") }) },
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.products.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Todavía no hay precios registrados. Cambia el precio de un producto en la despensa, " +
                            "indicando dónde lo compraste, y su evolución aparecerá aquí.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    ProductSelector(
                        products = state.products,
                        selected = state.selected,
                        onSelect = onSelectProduct,
                    )
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        PriceChart(
                            points = state.points,
                            legend = state.legend,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(8.dp),
                        )
                    }
                }
                state.summary?.let { summary -> item { SummaryRow(summary) } }
                item {
                    Text("Historial", style = MaterialTheme.typography.titleMedium)
                }
                // Del más reciente al más antiguo, con la diferencia respecto al registro anterior.
                val newestFirst = state.points.asReversed()
                itemsIndexed(newestFirst) { index, point ->
                    val previous = newestFirst.getOrNull(index + 1)
                    HistoryRow(point = point, previousCents = previous?.priceCents)
                    if (index < newestFirst.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ProductSelector(
    products: List<ProductOption>,
    selected: ProductOption?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Producto") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Capa transparente encima: el campo es de solo lectura y no abre el menú por sí mismo.
        Box(
            Modifier
                .matchParentSize()
                .clickable(onClickLabel = "Elegir producto") { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            products.forEach { product ->
                DropdownMenuItem(
                    text = { Text(product.name) },
                    onClick = {
                        onSelect(product.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(summary: PriceSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Último", euros(summary.lastCents))
        Stat("Mínimo", euros(summary.minCents))
        Stat("Máximo", euros(summary.maxCents))
        summary.changePercent?.let { pct ->
            Stat("Variación", String.format(spanish, "%+.1f %%", pct))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun HistoryRow(point: ChartPoint, previousCents: Long?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).background(storeColor(point.colorIndex), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(point.storeName, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatDate(point.recordedAt, longDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(euros(point.priceCents), style = MaterialTheme.typography.titleSmall)
            if (previousCents != null && previousCents != point.priceCents) {
                val diff = point.priceCents - previousCents
                Text(
                    (if (diff > 0) "+" else "−") + euros(kotlin.math.abs(diff)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (diff > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ============================================================
//  Gráfica (Canvas)
// ============================================================

// Márgenes del área de dibujo: a la izquierda van los precios y abajo las fechas.
private val PlotLeft = 56.dp
private val PlotRight = 12.dp
private val PlotTop = 12.dp
private val PlotBottom = 28.dp

@Composable
fun PriceChart(
    points: List<ChartPoint>,
    legend: List<StoreLegendEntry>,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val haloColor = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    val scale = remember(points) { ChartScale.from(points) }
    val legendCorner = remember(points, scale) { emptiestCorner(points, scale) }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val s = scale ?: return@Canvas
            val left = PlotLeft.toPx()
            val right = size.width - PlotRight.toPx()
            val top = PlotTop.toPx()
            val bottom = size.height - PlotBottom.toPx()
            val inset = 12.dp.toPx() // para que los puntos de los extremos no queden cortados

            fun xOf(t: Long) = left + inset + s.xFraction(t) * (right - left - 2 * inset)
            fun yOf(c: Long) = bottom - s.yFraction(c) * (bottom - top)

            // Rejilla horizontal y etiquetas de precio
            var value = s.minCents
            var guard = 0
            while (value <= s.maxCents && guard++ < 12) {
                val y = yOf(value)
                drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                val label = textMeasurer.measure(euros(value), labelStyle)
                drawText(
                    label,
                    topLeft = Offset(left - label.size.width - 6.dp.toPx(), y - label.size.height / 2f),
                )
                value += s.stepCents
            }

            // Fechas en el eje X: primera, última y una intermedia si hay sitio
            val dateTimes = buildList {
                add(s.minTime)
                if (s.maxTime != s.minTime) {
                    if (right - left > 260.dp.toPx()) add((s.minTime + s.maxTime) / 2)
                    add(s.maxTime)
                }
            }
            dateTimes.forEach { t ->
                val label = textMeasurer.measure(formatDate(t, shortDate), labelStyle)
                val x = (xOf(t) - label.size.width / 2f)
                    .coerceIn(left, right - label.size.width)
                drawText(label, topLeft = Offset(x, bottom + 6.dp.toPx()))
            }

            // Línea de evolución (neutra; el color lo llevan los puntos)
            if (points.size > 1) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = xOf(p.recordedAt)
                    val y = yOf(p.priceCents)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
            }

            // Puntos coloreados por tienda, con halo para que destaquen sobre la línea
            points.forEach { p ->
                val center = Offset(xOf(p.recordedAt), yOf(p.priceCents))
                drawCircle(haloColor, radius = 7.dp.toPx(), center = center)
                drawCircle(storeColor(p.colorIndex), radius = 5.dp.toPx(), center = center)
            }
        }

        // Leyenda en la esquina con menos puntos
        ChartLegend(
            legend = legend,
            modifier = Modifier
                .align(legendCorner.alignment)
                .padding(
                    start = PlotLeft + 4.dp,
                    end = PlotRight + 4.dp,
                    top = PlotTop + 4.dp,
                    bottom = PlotBottom + 4.dp,
                ),
        )
    }
}

@Composable
private fun ChartLegend(legend: List<StoreLegendEntry>, modifier: Modifier = Modifier) {
    if (legend.isEmpty()) return
    Surface(
        modifier = modifier.widthIn(max = 160.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            legend.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(storeColor(entry.colorIndex), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ============================================================
//  Escalas y posición de la leyenda (lógica pura)
// ============================================================

internal data class ChartScale(
    val minTime: Long,
    val maxTime: Long,
    val minCents: Long,
    val maxCents: Long,
    val stepCents: Long,
) {
    /** 0 = izquierda, 1 = derecha. Un único instante se centra. */
    fun xFraction(t: Long): Float =
        if (maxTime == minTime) 0.5f else (t - minTime).toFloat() / (maxTime - minTime)

    /** 0 = abajo, 1 = arriba. */
    fun yFraction(cents: Long): Float =
        (cents - minCents).toFloat() / (maxCents - minCents)

    companion object {
        fun from(points: List<ChartPoint>): ChartScale? {
            if (points.isEmpty()) return null
            val lo = points.minOf { it.priceCents }
            val hi = points.maxOf { it.priceCents }
            // Si todos los precios son iguales, damos un margen del 10 % (mínimo 10 céntimos).
            val range = maxOf(hi - lo, hi / 10, 10L)
            val step = niceStep(range / 4.0)
            val min = (floor((lo - range * 0.15) / step) * step).toLong().coerceAtLeast(0)
            var max = (ceil((hi + range * 0.15) / step) * step).toLong()
            if (max <= min) max = min + step
            return ChartScale(
                minTime = points.minOf { it.recordedAt },
                maxTime = points.maxOf { it.recordedAt },
                minCents = min,
                maxCents = max,
                stepCents = step,
            )
        }
    }
}

/** Paso "redondo" para la rejilla: 1, 2, 5, 10, 20, 50… céntimos. */
internal fun niceStep(raw: Double): Long {
    if (raw <= 1.0) return 1
    val magnitude = 10.0.pow(floor(log10(raw)))
    val fraction = raw / magnitude
    val nice = when {
        fraction <= 1 -> 1.0
        fraction <= 2 -> 2.0
        fraction <= 5 -> 5.0
        else -> 10.0
    }
    return (nice * magnitude).toLong().coerceAtLeast(1)
}

internal enum class LegendCorner(val alignment: Alignment) {
    TopEnd(Alignment.TopEnd),
    TopStart(Alignment.TopStart),
    BottomEnd(Alignment.BottomEnd),
    BottomStart(Alignment.BottomStart),
}

/**
 * Elige la esquina del gráfico con menos puntos para colocar la leyenda.
 * En caso de empate, el orden de preferencia es el del enum (arriba a la derecha primero).
 */
internal fun emptiestCorner(points: List<ChartPoint>, scale: ChartScale?): LegendCorner {
    if (scale == null || points.isEmpty()) return LegendCorner.TopEnd
    fun countIn(corner: LegendCorner): Int = points.count { p ->
        val x = scale.xFraction(p.recordedAt)
        val y = scale.yFraction(p.priceCents)
        val isLeft = x < 0.5f
        val isTop = y > 0.5f
        when (corner) {
            LegendCorner.TopEnd -> !isLeft && isTop
            LegendCorner.TopStart -> isLeft && isTop
            LegendCorner.BottomEnd -> !isLeft && !isTop
            LegendCorner.BottomStart -> isLeft && !isTop
        }
    }
    return LegendCorner.entries.minBy { countIn(it) }
}

// ============================================================
//  Preview
// ============================================================

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PriceChartScreenPreview() {
    val day = 24L * 60 * 60 * 1000
    val t0 = 1_780_000_000_000L
    val points = listOf(
        ChartPoint(t0, 105, "Mercadona", 0),
        ChartPoint(t0 + 12 * day, 99, "Lidl", 1),
        ChartPoint(t0 + 30 * day, 115, "Mercadona", 0),
        ChartPoint(t0 + 45 * day, 119, "Gadis", 2),
        ChartPoint(t0 + 70 * day, 125, "Mercadona", 0),
    )
    MaterialTheme {
        PriceChartScreen(
            state = PriceChartUiState(
                isLoading = false,
                products = listOf(ProductOption(1, "Leche entera Hacendado 1 l")),
                selected = ProductOption(1, "Leche entera Hacendado 1 l"),
                points = points,
                legend = listOf(
                    StoreLegendEntry("Gadis", 2),
                    StoreLegendEntry("Lidl", 1),
                    StoreLegendEntry("Mercadona", 0),
                ),
                summary = points.toSummary(),
            ),
            onSelectProduct = {},
        )
    }
}