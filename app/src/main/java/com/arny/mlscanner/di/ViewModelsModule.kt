package com.arny.mlscanner.di

import com.arny.mlscanner.domain.usecases.barcode.ScanBarcodeUseCase
import com.arny.mlscanner.ui.screens.BarcodeScannerViewModel
import com.arny.mlscanner.ui.screens.ScanViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { ScanViewModel(get(), get(), get()) }
    viewModel { BarcodeScannerViewModel(get()) }
}
