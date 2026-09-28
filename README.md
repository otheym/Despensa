# Despensa

[![Licencia: GPL v3](https://img.shields.io/badge/licencia-GPL--3.0--or--later-blue.svg)](LICENSE)

Aplicación Android para llevar el control de la despensa de casa, generar la lista de la compra automáticamente y seguir la evolución de los precios en cada comercio.

Funciona **sin cuentas, sin servidores y sin anuncios**: los datos viven en el móvil y se sincronizan entre dispositivos mediante un fichero que se envía por correo, Bluetooth o Quick Share.

---

## Funciones

### La despensa
- Lista de productos con **semáforo de stock**:
  - 🔴 **Agotado**: cantidad 0, pasa a la lista de la compra.
  - 🟡 **Queda poco**: 1 o 2 unidades.
  - 🟢 **Stock suficiente**: 3 o más.
- Ordenación automática de rojo a verde, con cabeceras por estado y animación cuando un producto cambia de grupo.
- Botones `[ − ]` y `[ + ]` para ajustar el stock con un toque.
- Búsqueda por nombre o código y alta rápida desde el buscador.
- Edición de nombre y precio tocando el producto.

### Escaneo de códigos de barras
- Escáner de **Google ML Kit** (Code Scanner de Play Services). No necesita permiso de cámara.
- Si el código ya está registrado, suma una unidad.
- Si es nuevo, busca el nombre en línea en **Open Food Facts**, **Open Products Facts** y **Open Beauty Facts**, y lo propone para que lo confirmes o lo corrijas antes de guardar.

### Lista de la compra
- Se genera sola con los productos agotados y, opcionalmente, con los que quedan pocos.
- Hay dos formas de marcar un producto como comprado:
  - **Casilla**: compra rápida, con opción de deshacer.
  - **Tocar el producto**: permite indicar unidades, precio y tienda.
- Coste estimado a partir de los últimos precios conocidos.
- **Envío por WhatsApp** con formato legible, o por cualquier otra app mediante el menú de compartir.
- Contador de productos agotados en la pestaña.

### Historial de precios
- Cada cambio de precio se guarda con **fecha y lugar de compra**.
- Gráfica por producto con un **punto de color por comercio**. Cada tienda conserva su color en todas las gráficas.
- La leyenda se coloca automáticamente en la zona de la gráfica con menos puntos.
- Resumen con último precio, mínimo, máximo y variación, e historial detallado con las subidas y bajadas.

### Sincronización entre dispositivos
- Exporta el estado completo a un fichero `despensa-sync-FECHA.json` y lo envía por **correo, Bluetooth, Quick Share** o cualquier app de mensajería.
- Al importar, **fusiona** los datos sin perder cambios de ninguno de los móviles (ver [Diseño de la sincronización](#diseño-de-la-sincronización)).
- Importar dos veces el mismo fichero, o ficheros en cualquier orden, siempre da el mismo resultado.

---

## Tecnología

| Área | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| Interfaz | Jetpack Compose + Material 3 |
| Arquitectura | MVVM: `ViewModel` + `StateFlow`, pantallas sin estado |
| Base de datos | Room (SQLite) con migraciones versionadas |
| Escáner | Google ML Kit Code Scanner (Play Services) |
| Búsqueda de productos | API v2 de Open Food Facts (`HttpURLConnection` + `org.json`, sin dependencias extra) |
| Gráficas | `Canvas` de Compose, sin librerías externas |
| Compartir ficheros | `FileProvider` + `Intent.ACTION_SEND` |

**Requisitos:** Android 8.0 (API 26) o superior. Para el escáner hace falta Google Play Services.

---

## Estructura del proyecto

```
app/src/main/java/com/example/despensa/
├── MainActivity.kt              # Punto de entrada, escáner y creación de dependencias
├── data/
│   ├── Product.kt               # Entidades Product y StockEvent, semáforo StockStatus
│   ├── ProductDao.kt            # Consultas de productos y movimientos de stock
│   ├── PriceHistory.kt          # Entidades Store y PriceRecord, PriceDao
│   ├── ProductLookup.kt         # Cliente de Open Food Facts y bases hermanas
│   ├── Sync.kt                  # Formato del fichero, fusión (SyncDao) y SyncRepository
│   ├── PantryRepository.kt      # Punto único de acceso a los datos
│   └── PantryDatabase.kt        # Base de datos Room y migraciones
└── ui/
    ├── AppRoot.kt               # Navegación inferior por pestañas
    ├── pantry/                  # La despensa
    ├── shopping/                # Lista de la compra
    ├── prices/                  # Gráfica de precios
    └── sync/                    # Sincronización

app/src/main/res/xml/file_paths.xml   # Rutas compartidas por FileProvider
```

---

## Modelo de datos

```
products ──< stock_events         (movimientos de stock: +2, −1…)
    │
    └────< price_records >──── stores
```

- **`products`**: nombre, código de barras (opcional y único), último precio, cantidad (caché) y un `uid` universal compartido entre dispositivos.
- **`stock_events`**: cada cambio de cantidad es un movimiento inmutable. La cantidad real es la suma de los movimientos.
- **`stores`**: comercios. Los nombres no distinguen mayúsculas.
- **`price_records`**: historial de precios (producto, tienda, precio en céntimos y fecha).

Los precios se guardan en **céntimos** (`Long`) para evitar errores de redondeo.

### Versiones de la base de datos

| Versión | Cambios |
|---|---|
| 1 | Tabla de productos |
| 2 | Tiendas e historial de precios |
| 3 | Identificadores universales y movimientos de stock para la sincronización |

Todas las migraciones conservan los datos existentes.

---

## Diseño de la sincronización

La sincronización no necesita servidor. Cada dispositivo exporta **su estado completo** a un fichero JSON (unos pocos kilobytes), y el receptor lo fusiona con el suyo según estas reglas:

| Dato | Regla de fusión |
|---|---|
| Identidad del producto | Se empareja por `uid`, luego por código de barras y luego por nombre. Si dos `uid` distintos se emparejan, ambos dispositivos adoptan el menor y convergen. |
| Cantidad | Se unen los movimientos de ambos lados y la cantidad es su suma. Un consumo en un móvil y una compra en otro se aplican los dos. |
| Nombre | Gana la edición más reciente. |
| Código de barras | Se adopta si está libre y nunca se elimina uno existente. |
| Tiendas y precios | Se añaden los que falten, sin duplicar. |

Estas reglas hacen que la fusión sea **idempotente y conmutativa**. No hace falta llevar la cuenta de qué se envió a quién.

**Uso recomendado:**
1. Móvil A → "Enviar mis datos".
2. Móvil B → "Elegir fichero".
3. Repetir en sentido contrario para que A reciba los cambios de B.

> **Importante:** la primera vez, el segundo dispositivo debe empezar vacío e importar los datos del primero. Si ambos se rellenan por separado y después se unen, las cantidades iniciales se sumarían. También conviene tener la hora automática activada en todos los dispositivos.

---

## Compilación

1. Clona el repositorio y ábrelo con Android Studio.
2. Comprueba estas entradas en `gradle/libs.versions.toml`:

   ```toml
   [versions]
   room = "2.8.5"
   ksp = "2.3.10"
   codeScanner = "16.1.0"
   ```

   Dependencias principales: `room-runtime`, `room-ktx` y `room-compiler` (vía KSP), `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` y `play-services-code-scanner`.

3. En `MainActivity.kt`, sustituye el email del **User-Agent** por el tuyo. Open Food Facts pide que cada app se identifique:

   ```kotlin
   OpenFactsLookup(userAgent = "Despensa/1.0 (tu-email@ejemplo.com)")
   ```

4. Compila y ejecuta. Para probar el escáner usa un dispositivo real o un emulador con imagen **Google Play**.

### Permisos y manifiesto

- `android.permission.INTERNET`: búsqueda de nombres de producto en línea.
- `FileProvider` con autoridad `${applicationId}.fileprovider`: envío del fichero de sincronización.

La app no solicita permisos de cámara, ubicación ni contactos.

---

## Privacidad

- Todos los datos se guardan **solo en el dispositivo**.
- La única conexión a Internet es la consulta del código de barras a Open Food Facts cuando se escanea un producto desconocido. Solo se envía el código.
- El fichero de sincronización contiene tus productos, cantidades y precios. Tú decides a quién y por qué medio lo envías.

---

## Datos de productos

Los nombres sugeridos proceden de [Open Food Facts](https://world.openfoodfacts.org), [Open Products Facts](https://world.openproductsfacts.org) y [Open Beauty Facts](https://world.openbeautyfacts.org), bases de datos colaborativas publicadas bajo la licencia [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/1-0/). Si un producto no aparece o su información es incorrecta, puedes añadirlo o corregirlo en su web.

---

## Hoja de ruta

- [ ] Abrir el fichero de sincronización directamente desde el adjunto del correo.
- [ ] Sincronización Bluetooth directa entre dispositivos.
- [ ] Copia de seguridad y restauración manual.
- [ ] Borrado de productos, propagado en la sincronización.

---

## Licencia

Despensa es **software libre**: puedes usarlo, estudiarlo, modificarlo y redistribuirlo según los términos de la **GNU General Public License v3.0 o posterior** (GPL-3.0-or-later), publicada por la Free Software Foundation.

Cualquier versión modificada que se distribuya debe publicarse también bajo esta licencia y con su código fuente.

Este programa se distribuye con la esperanza de que sea útil, pero **SIN NINGUNA GARANTÍA**, ni siquiera la garantía implícita de COMERCIABILIDAD o IDONEIDAD PARA UN PROPÓSITO PARTICULAR. Consulta la licencia completa en [`LICENSE`](LICENSE).

### Excepción para Google Play Services

La app usa el escáner de códigos de **Google ML Kit (Play Services)**, que es software propietario. Para permitir esa combinación, la licencia incluye un permiso adicional según la sección 7 de la GPL-3.0, recogido en [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md).

Copyright © 2026 Otheym
