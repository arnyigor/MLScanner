package com.arny.mlscanner

import android.app.Application
import com.arny.mlscanner.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MlScannerApp: Application() {
    override fun onCreate() {
        super.onCreate()

        System.loadLibrary("opencv_java4")
        System.loadLibrary("sqlcipher")

        startKoin {
            // 1️⃣ Android context (необходим для кода, зависящего от контекста)
            androidContext(this@MlScannerApp)

            // 2️⃣ Путь к модулям
            modules(appModule)
        }
    }
}