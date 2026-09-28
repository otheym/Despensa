package com.casa.despensa.ui.sync



import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.casa.despensa.data.ImportSummary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
//  Route: ficheros y menú de compartir (necesitan Context)
// ============================================================

@Composable
fun SyncRoute(
    viewModel: SyncViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { readText(context, uri) }
            if (text == null) viewModel.onError("No se pudo leer el fichero.") else viewModel.importText(text)
        }
    }

    SyncScreen(
        state = state,
        onExport = {
            scope.launch {
                try {
                    val json = viewModel.buildExport(deviceName())
                    val uri = withContext(Dispatchers.IO) { writeSyncFile(context, json) }
                    shareSyncFile(context, uri)
                    viewModel.onExported()
                } catch (e: Exception) {
                    viewModel.onError("No se pudo preparar el fichero: ${e.message}")
                }
            }
        },
        // "*/*": según cómo llegue (correo, Bluetooth, Quick Share) el sistema le asigna tipos distintos.
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        modifier = modifier,
    )
}

private fun deviceName(): String =
    listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" ")
        .ifBlank { "otro móvil" }

private fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
} catch (e: Exception) {
    null
}

/** Guarda el JSON en cache/sync (compartido mediante FileProvider). Borra exportaciones anteriores. */
private fun writeSyncFile(context: Context, json: String): Uri {
    val dir = File(context.cacheDir, "sync").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(
        Instant.now().atZone(ZoneId.systemDefault())
    )
    val file = File(dir, "despensa-sync-$stamp.json")
    file.writeText(json)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Menú de compartir con el fichero adjunto: correo, Bluetooth, Quick Share, WhatsApp…
 * Tipo text/plain: el servicio Bluetooth de Android solo acepta ciertos tipos de fichero
 * y el texto plano está en todos; el contenido sigue siendo JSON.
 */
private fun shareSyncFile(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Despensa: datos para sincronizar")
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Enviar datos de la despensa"))
}

// ============================================================
//  Pantalla
// ============================================================

private val dateTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es-ES"))
private fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTime)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    state: SyncUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Sincronizar") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth())

            SectionCard(
                title = "Enviar mis datos",
                text = "Crea un fichero con los productos, el stock y los precios de este móvil. " +
                        "Envíalo por correo, Bluetooth o Quick Share al otro móvil.",
            ) {
                Button(onClick = onExport, enabled = !state.isWorking) { Text("Enviar mis datos") }
                state.lastExportAt?.let {
                    Text(
                        "Último envío: ${formatDateTime(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(
                title = "Importar datos recibidos",
                text = "Elige el fichero que te han enviado (normalmente en Descargas o como adjunto del correo). " +
                        "Se fusiona con lo que ya tienes: no se pierde ningún cambio de ninguno de los dos móviles.",
            ) {
                OutlinedButton(onClick = onImport, enabled = !state.isWorking) { Text("Elegir fichero") }
            }

            state.error?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            state.lastImport?.let { ImportResultCard(it) }

            SectionCard(
                title = "Cómo sincronizar dos móviles",
                text = "1. En el móvil A: «Enviar mis datos».\n" +
                        "2. En el móvil B: «Elegir fichero» con lo recibido.\n" +
                        "3. Para que A reciba también los cambios de B, repetid al revés.\n\n" +
                        "La primera vez: instala la app en el segundo móvil e importa los datos del primero " +
                        "antes de apuntar nada en él. Si llenáis los dos por separado, al unirlos se sumarían " +
                        "las cantidades de ambos.\n\n" +
                        "Conviene que los dos móviles tengan la hora automática activada.",
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    text: String,
    content: @Composable () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun ImportResultCard(summary: ImportSummary) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Importación completada", style = MaterialTheme.typography.titleMedium)
            Text(
                "Datos de ${summary.sourceDevice}, enviados el ${formatDateTime(summary.exportedAt)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.nothingNew) {
                Text("Ya estaba todo al día.")
            } else {
                Text(
                    listOf(
                        count(summary.newProducts, "producto nuevo", "productos nuevos"),
                        count(summary.updatedProducts, "producto actualizado", "productos actualizados"),
                        count(summary.newMovements, "movimiento de stock", "movimientos de stock"),
                        count(summary.newPrices, "precio nuevo", "precios nuevos"),
                    ).joinToString("\n")
                )
            }
        }
    }
}

private fun count(n: Int, singular: String, plural: String) = "• $n ${if (n == 1) singular else plural}"

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun SyncScreenPreview() {
    MaterialTheme {
        SyncScreen(
            state = SyncUiState(
                lastImport = ImportSummary("Samsung SM-A546B", 1_790_000_000_000, 3, 1, 14, 5),
                lastExportAt = 1_790_000_600_000,
            ),
            onExport = {},
            onImport = {},
        )
    }
}