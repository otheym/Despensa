package com.casa.despensa.ui




import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casa.despensa.ui.pantry.PantryRoute
import com.casa.despensa.ui.pantry.PantryViewModel
import com.casa.despensa.ui.prices.PriceChartRoute
import com.casa.despensa.ui.prices.PriceChartViewModel
import com.casa.despensa.ui.shopping.ShoppingListRoute
import com.casa.despensa.ui.shopping.ShoppingListViewModel
import com.casa.despensa.ui.sync.SyncRoute
import com.casa.despensa.ui.sync.SyncViewModel

enum class AppTab(val label: String, val icon: ImageVector) {
    PANTRY("Despensa", Icons.Default.Home),
    SHOPPING("Compra", Icons.Default.ShoppingCart),
    PRICES("Precios", Icons.Default.DateRange),
    SYNC("Sincronizar", Icons.Default.Refresh),
}

/** Contenedor principal: barra de navegación inferior con una pestaña por pantalla. */
@Composable
fun AppRoot(
    pantryViewModel: PantryViewModel,
    shoppingListViewModel: ShoppingListViewModel,
    priceChartViewModel: PriceChartViewModel,
    syncViewModel: SyncViewModel,
    onScanClick: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AppTab.PANTRY) }
    // Para el contador de productos agotados en la pestaña "Compra".
    val shoppingState by shoppingListViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        // Las pantallas interiores tienen su propia barra superior: aquí solo gestionamos la inferior.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            val count = if (item == AppTab.SHOPPING) shoppingState.outOfStockCount else 0
                            if (count > 0) {
                                BadgedBox(badge = { Badge { Text(count.toString()) } }) {
                                    Icon(item.icon, contentDescription = null)
                                }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets evita que las pantallas interiores vuelvan a sumar el margen inferior.
        val content = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
        when (tab) {
            AppTab.PANTRY -> PantryRoute(
                viewModel = pantryViewModel,
                onScanClick = onScanClick,
                modifier = content,
            )
            AppTab.SHOPPING -> ShoppingListRoute(
                viewModel = shoppingListViewModel,
                modifier = content,
            )
            AppTab.PRICES -> PriceChartRoute(
                viewModel = priceChartViewModel,
                modifier = content,
            )
            AppTab.SYNC -> SyncRoute(
                viewModel = syncViewModel,
                modifier = content,
            )
        }
    }
}