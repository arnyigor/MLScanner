package com.arny.mlscanner.di

import com.arny.mlscanner.domain.formatters.TextFormatter
import com.arny.mlscanner.domain.mappers.OcrResultMapper
import com.arny.mlscanner.domain.usecases.OcrRepository
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import com.arny.mlscanner.domain.usecases.barcode.ScanBarcodeUseCase
import org.koin.dsl.module

val domainModule = module {
    factory { TextFormatter() }
    factory { OcrResultMapper(get()) }

    factory { RecognizeTextUseCase(get(), get()) }
    factory { ScanBarcodeUseCase(get()) }
}