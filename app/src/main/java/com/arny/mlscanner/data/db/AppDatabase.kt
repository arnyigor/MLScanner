package com.arny.mlscanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.arny.mlscanner.data.prefs.SecurePrefs
import net.zetetic.database.sqlcipher.SQLiteDatabase
// Enable SQLCipher support in Room via SupportFactory
import net.zetetic.database.sqlcipher.support.SupportFactory

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
                // Create a SupportFactory with the generated key
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
