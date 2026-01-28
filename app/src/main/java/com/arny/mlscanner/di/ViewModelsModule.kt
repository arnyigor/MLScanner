package com.arny.mlscanner.di

import com.arny.mlscanner.ui.screens.AdvancedScanViewModel
import com.arny.mlscanner.ui.screens.ScanViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { ScanViewModel(get(), get()) }
    viewModel { AdvancedScanViewModel(get(), get(), get()) }
}
