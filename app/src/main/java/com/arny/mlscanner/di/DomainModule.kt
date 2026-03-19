package com.arny.mlscanner.di

import com.arny.mlscanner.domain.formatters.TextFormatter
import com.arny.mlscanner.domain.mappers.OcrResultMapper
import com.arny.mlscanner.domain.usecases.OcrRepository
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import org.koin.dsl.module

val domainModule = module {
    // Форматтеры и мапперы
    factory { TextFormatter() }
    factory { OcrResultMapper(get()) }

    // UseCases
    factory { RecognizeTextUseCase(get(), get()) } // OcrRepository, OcrResultMapper
}