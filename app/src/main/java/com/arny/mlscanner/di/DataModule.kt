package com.arny.mlscanner.di

import com.arny.mlscanner.data.db.AppDatabase
import com.arny.mlscanner.data.matching.MatchingEngine
import com.arny.mlscanner.data.ocr.OcrRepositoryImpl
import com.arny.mlscanner.data.pdf.PdfRedactionEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.data.prefs.Prefs
import com.arny.mlscanner.data.prefs.SecurePrefs
import com.arny.mlscanner.domain.usecases.OcrRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    // Prefs
    single { Prefs.getInstance(get()) }
    single { SecurePrefs.getInstance(get()) }

    // Dispatcher
    single<CoroutineDispatcher> { Dispatchers.IO }

    // Database
    single {
        AppDatabase.getDatabase(androidContext())
    }
    single {
        get<AppDatabase>().productDao()
    }

    // Preprocessing
    single { ImagePreprocessor(androidContext()) }

    // OCR Repository (единственная точка доступа к OCR)
    single<OcrRepository> {
        OcrRepositoryImpl(
            context = androidContext(),
            imagePreprocessor = get()
        )
    }

    // Other data components
    single { PdfRedactionEngine(androidContext()) }
    single { MatchingEngine(get()) }
}
