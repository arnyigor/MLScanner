package com.arny.mlscanner.di

import com.arny.mlscanner.domain.usecases.AdvancedRecognizeTextUseCase
import org.koin.dsl.module
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase

val domainModule = module {
    // Koin сам найдет зависимости для конструктора Interactor'а
    // factory - создаем каждый раз новый (безопаснее для stateful интеракторов)
    // single - один на все приложение
    factory { RecognizeTextUseCase(get(), get()) }
    factory { AdvancedRecognizeTextUseCase(get(), get()) }
}