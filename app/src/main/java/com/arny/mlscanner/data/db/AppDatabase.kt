package com.arny.mlscanner.data.db

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.arny.mlscanner.data.prefs.SecurePrefs
// Импорт правильной фабрики
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

@Database(
    entities = [ProductEntity::class, ProductFtsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val securePrefs = SecurePrefs.getInstance(context)
                val keyAlias = "db_key_material"

                var keyString = securePrefs.getSecureString(keyAlias, "")
                if (keyString.isEmpty()) {
                    val randomBytes = ByteArray(32)
                    SecureRandom().nextBytes(randomBytes)
                    // Генерируем ключ и сохраняем как Base64 строку
                    keyString = Base64.encodeToString(
                        randomBytes,
                        Base64.NO_WRAP
                    )
                    securePrefs.saveSecureString(keyAlias, keyString)
                }

                // ИСПРАВЛЕНИЕ:
                // 1. Используем стандартный toByteArray() вместо приватного SQLiteDatabase.getBytes
                val passphrase = keyString.toByteArray()

                // 2. Используем правильное имя класса: SupportOpenHelperFactory
                val factory = SupportOpenHelperFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "product_database"
                )
                    .openHelperFactory(factory) // Включаем шифрование SQLCipher
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}