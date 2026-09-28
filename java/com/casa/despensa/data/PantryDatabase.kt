package com.casa.despensa.data


import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Product::class, StockEvent::class, Store::class, PriceRecord::class],
    version = 3,
    exportSchema = true,
)
abstract class PantryDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun priceDao(): PriceDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var instance: PantryDatabase? = null

        fun get(context: Context): PantryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PantryDatabase::class.java,
                    "pantry.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * v1 → v2: añade tiendas e historial de precios. Los productos existentes se conservan.
 * El SQL debe coincidir exactamente con lo que Room espera de las entidades Store y PriceRecord;
 * si se cambian esas clases, hay que hacer una migración nueva (v2 → v3), no tocar esta.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stores` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL COLLATE NOCASE)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stores_name` ON `stores` (`name`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `price_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`product_id` INTEGER NOT NULL, " +
                    "`store_id` INTEGER NOT NULL, " +
                    "`price_cents` INTEGER NOT NULL, " +
                    "`recorded_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`store_id`) REFERENCES `stores`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_price_records_product_id_recorded_at` " +
                    "ON `price_records` (`product_id`, `recorded_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_price_records_store_id` ON `price_records` (`store_id`)"
        )
    }
}

/**
 * v2 → v3: sincronización entre móviles.
 * - products gana `uid` (identificador compartido) y `meta_updated_at` (fecha de la última edición).
 *   SQLite no permite añadir columnas NOT NULL sin valor por defecto, así que se recrea la tabla
 *   (procedimiento estándar: crear nueva, copiar, borrar vieja, renombrar). Los id se conservan,
 *   por lo que el historial de precios sigue apuntando a sus productos.
 * - Nueva tabla stock_events. La cantidad actual de cada producto se guarda como primer movimiento.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `products_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`uid` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`barcode` TEXT, " +
                    "`last_price_cents` INTEGER, " +
                    "`quantity` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "`meta_updated_at` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `products_new` " +
                    "(`id`, `uid`, `name`, `barcode`, `last_price_cents`, `quantity`, `updated_at`, `meta_updated_at`) " +
                    "SELECT `id`, lower(hex(randomblob(16))), `name`, `barcode`, `last_price_cents`, " +
                    "`quantity`, `updated_at`, `updated_at` FROM `products`"
        )
        db.execSQL("DROP TABLE `products`")
        db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_products_uid` ON `products` (`uid`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_name` ON `products` (`name`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stock_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`uid` TEXT NOT NULL, " +
                    "`product_id` INTEGER NOT NULL, " +
                    "`delta` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`product_id`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_events_uid` ON `stock_events` (`uid`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_events_product_id` ON `stock_events` (`product_id`)")
        db.execSQL(
            "INSERT INTO `stock_events` (`uid`, `product_id`, `delta`, `created_at`) " +
                    "SELECT lower(hex(randomblob(16))), `id`, `quantity`, `updated_at` " +
                    "FROM `products` WHERE `quantity` <> 0"
        )
    }
}