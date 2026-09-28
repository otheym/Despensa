package com.casa.despensa.data



import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Producto encontrado en línea: nombre ya compuesto (nombre + marca + cantidad) y fuente. */
data class OnlineProduct(
    val suggestedName: String,
    val source: String,
)

sealed interface LookupResult {
    data class Found(val product: OnlineProduct) : LookupResult
    /** Ninguna base de datos conoce el código. */
    data object NotFound : LookupResult
    /** Sin conexión, tiempo agotado o error del servidor. */
    data object Unavailable : LookupResult
}

/** Interfaz para poder sustituirla por una falsa en tests o en la @Preview. */
fun interface ProductLookup {
    suspend fun lookup(barcode: String): LookupResult
}

/**
 * Consulta encadenada a las bases de datos abiertas de Open Food Facts:
 * alimentación → productos de consumo (limpieza, hogar…) → cosmética e higiene.
 *
 * Sin dependencias externas: HttpURLConnection + org.json vienen con Android.
 * Solo lectura, sin clave de API; Open Food Facts pide un User-Agent que identifique la app:
 * "NombreApp/Versión (email de contacto)".
 */
class OpenFactsLookup(
    private val userAgent: String,
    private val timeoutMs: Int = 4_000,
) : ProductLookup {

    private val sources = listOf(
        "Open Food Facts" to "https://world.openfoodfacts.org",
        "Open Products Facts" to "https://world.openproductsfacts.org",
        "Open Beauty Facts" to "https://world.openbeautyfacts.org",
    )

    override suspend fun lookup(barcode: String): LookupResult {
        val code = barcode.trim()
        if (!isRetailBarcode(code)) return LookupResult.NotFound

        for ((label, baseUrl) in sources) {
            when (val result = fetch(baseUrl, code)) {
                is Fetch.Hit -> return LookupResult.Found(OnlineProduct(result.name, label))
                Fetch.Miss -> continue
                // Si falla la primera (normalmente por falta de conexión), no insistimos con las demás:
                // así el diálogo no se queda esperando varias veces el timeout.
                Fetch.Error -> return LookupResult.Unavailable
            }
        }
        return LookupResult.NotFound
    }

    private sealed interface Fetch {
        data class Hit(val name: String) : Fetch
        data object Miss : Fetch
        data object Error : Fetch
    }

    private suspend fun fetch(baseUrl: String, barcode: String): Fetch = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/api/v2/product/$barcode?fields=$FIELDS")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")

            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parseSuggestedName(body)?.let { Fetch.Hit(it) } ?: Fetch.Miss
                }
                HttpURLConnection.HTTP_NOT_FOUND -> Fetch.Miss
                else -> Fetch.Error
            }
        } catch (e: IOException) {
            Fetch.Error
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Pedimos solo los campos necesarios: respuesta de pocos cientos de bytes. */
        private const val FIELDS =
            "product_name_es,product_name,generic_name_es,generic_name,brands,quantity"
    }
}

// ============================================================
//  Lógica pura (testeable sin red)
// ============================================================

/** EAN-8, UPC-A (12), EAN-13 y GTIN-14. Descarta QR, códigos internos, etc. */
internal fun isRetailBarcode(code: String): Boolean =
    code.length in setOf(8, 12, 13, 14) && code.all { it.isDigit() }

/** Devuelve el nombre sugerido o null si el JSON no trae un producto con nombre. */
internal fun parseSuggestedName(body: String): String? = try {
    val root = JSONObject(body)
    if (root.optInt("status") != 1) {
        null
    } else {
        val product = root.optJSONObject("product")
        if (product == null) {
            null
        } else {
            val name = listOf("product_name_es", "product_name", "generic_name_es", "generic_name")
                .map { product.text(it) }
                .firstOrNull { it.isNotBlank() }
            name?.let {
                buildSuggestedName(
                    name = it,
                    brand = product.text("brands").split(',').firstOrNull().orEmpty(),
                    quantity = product.text("quantity"),
                )
            }
        }
    }
} catch (e: JSONException) {
    null
}

/** optString devuelve "null" (texto) para valores JSON null; esto lo evita. */
private fun JSONObject.text(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

/**
 * "LECHE ENTERA" + "HACENDADO" + "1 l" → "Leche entera Hacendado 1 l".
 * No repite la marca ni la cantidad si ya van en el nombre.
 */
internal fun buildSuggestedName(name: String, brand: String, quantity: String): String {
    val cleanName = name.normalizeCase()
    val cleanBrand = brand.trim().normalizeCase()
    val cleanQuantity = quantity.trim()

    val parts = mutableListOf(cleanName)
    if (cleanBrand.isNotEmpty() && !cleanName.contains(cleanBrand, ignoreCase = true)) parts += cleanBrand
    if (cleanQuantity.isNotEmpty() && !cleanName.contains(cleanQuantity, ignoreCase = true)) parts += cleanQuantity

    return parts.joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
}

private val spanish: Locale = Locale.forLanguageTag("es-ES")

/** Textos TODO EN MAYÚSCULAS → "Primera en mayúscula". El resto se deja igual. */
private fun String.normalizeCase(): String {
    val text = trim()
    val letters = text.filter { it.isLetter() }
    return if (letters.length > 3 && letters.all { it.isUpperCase() }) {
        text.lowercase(spanish).replaceFirstChar { it.titlecase(spanish) }
    } else {
        text
    }
}