package com.casa.despensa



import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.casa.despensa.data.OpenFactsLookup
import com.casa.despensa.data.PantryDatabase
import com.casa.despensa.data.PantryRepository
import com.casa.despensa.data.SyncRepository
import com.casa.despensa.ui.AppRoot
import com.casa.despensa.ui.pantry.PantryViewModel
import com.casa.despensa.ui.prices.PriceChartViewModel
import com.casa.despensa.ui.shopping.ShoppingListViewModel
import com.casa.despensa.ui.sync.SyncViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {

    private val syncRepository by lazy {
        SyncRepository(PantryDatabase.get(this).syncDao())
    }

    private val repository by lazy {
        val db = PantryDatabase.get(this)
        PantryRepository(
            dao = db.productDao(),
            priceDao = db.priceDao(),
            // Open Food Facts pide identificar la app: "NombreApp/Versión (email de contacto)".
            lookup = OpenFactsLookup(userAgent = "Despensa/1.0 (tucorreo@dedondesea.com)"),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Escáner de Google Play Services: UI propia, sin permiso de cámara ni CameraX.
        val scanner = GmsBarcodeScanning.getClient(
            this,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A)
                .enableAutoZoom()
                .build(),
        )

        setContent {
            MaterialTheme { // o el tema de tu proyecto, p. ej. DespensaTheme
                val pantryVm: PantryViewModel = viewModel(factory = PantryViewModel.factory(repository))
                val shoppingVm: ShoppingListViewModel = viewModel(factory = ShoppingListViewModel.factory(repository))
                val pricesVm: PriceChartViewModel = viewModel(factory = PriceChartViewModel.factory(repository))
                val syncVm: SyncViewModel = viewModel(factory = SyncViewModel.factory(syncRepository))
                AppRoot(
                    pantryViewModel = pantryVm,
                    shoppingListViewModel = shoppingVm,
                    priceChartViewModel = pricesVm,
                    syncViewModel = syncVm,
                    onScanClick = {
                        scanner.startScan()
                            .addOnSuccessListener { barcode -> pantryVm.onBarcodeScanned(barcode.rawValue) }
                    },
                )
            }
        }
    }
}