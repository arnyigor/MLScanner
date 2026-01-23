package com.arny.mlscanner.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.arny.mlscanner.data.prefs.SecurePrefs
import net.sqlcipher.database.SQLiteDatabase  // ← This is critical
import net.sqlcipher.database.SupportFactory

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
                    java.security.SecureRandom().nextBytes(randomBytes)
                    keyString = android.util.Base64.encodeToString(
                        randomBytes,
                        android.util.Base64.NO_WRAP
                    )
                    securePrefs.saveSecureString(keyAlias, keyString)
                }

                val key = SQLiteDatabase.getBytes(keyString.toCharArray())
                val factory = SupportFactory(key)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "product_database"
                )
                    .openHelperFactory(factory) // Enable SQLCipher encryption
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
