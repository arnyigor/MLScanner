package com.arny.mlscanner.di

import org.koin.dsl.module

val appModule = module {
    includes(
        networkModule,
        dataModule,
        domainModule,
        viewModelModule,
        barcodeModule
    )
}
