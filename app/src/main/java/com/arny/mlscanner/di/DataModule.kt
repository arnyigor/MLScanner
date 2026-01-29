package com.arny.mlscanner.di

import com.arny.mlscanner.data.matching.MatchingEngine
import com.arny.mlscanner.data.ocr.TesseractEngine
import com.arny.mlscanner.data.prefs.Prefs
import com.arny.mlscanner.data.prefs.SecurePrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.arny.mlscanner.data.ocr.TextFormatPreserver
import com.arny.mlscanner.data.pdf.PdfRedactionEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.data.security.LicenseManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { Prefs.getInstance(get()) }
    single { SecurePrefs.getInstance(get()) }
    single<CoroutineDispatcher> { Dispatchers.IO }   // можно вынести в отдельный модуль
    single { ImagePreprocessor(context = androidContext()) }
    single { TextFormatPreserver() }
    single { TesseractEngine(androidContext()) }
    single { PdfRedactionEngine(androidContext()) }
    single { MatchingEngine(get()) }
    single { LicenseManager(get()) }
}
