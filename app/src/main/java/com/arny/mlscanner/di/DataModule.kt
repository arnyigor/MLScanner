package com.arny.mlscanner.di

import com.arny.mlscanner.data.prefs.Prefs
import com.arny.mlscanner.data.prefs.SecurePrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.arny.mlscanner.data.ocr.TextFormatPreserver
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import org.koin.dsl.module

val dataModule = module {
    single { Prefs.getInstance(get()) }
    single { SecurePrefs.getInstance(get()) }
    single<CoroutineDispatcher> { Dispatchers.IO }   // можно вынести в отдельный модуль
    single { ImagePreprocessor() }
    single { TextFormatPreserver() }
}
