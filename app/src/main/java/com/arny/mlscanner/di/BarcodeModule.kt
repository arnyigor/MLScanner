package com.arny.mlscanner.di

import com.arny.mlscanner.data.barcode.analyzer.BarcodeCameraAnalyzer
import com.arny.mlscanner.data.barcode.engine.HybridBarcodeEngine
import com.arny.mlscanner.data.barcode.engine.MLKitBarcodeEngine
import com.arny.mlscanner.data.barcode.engine.ZXingBarcodeEngine
import com.arny.mlscanner.data.barcode.engine.BarcodeEngine
import com.arny.mlscanner.data.postprocessing.TextPostProcessor
import org.koin.dsl.module

val barcodeModule = module {
    single { MLKitBarcodeEngine() }
    single { ZXingBarcodeEngine() }
    single<BarcodeEngine> {
        HybridBarcodeEngine(
            mlKitEngine = get<MLKitBarcodeEngine>(),
            zxingEngine = get<ZXingBarcodeEngine>()
        )
    }

    factory { (config: com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig) ->
        BarcodeCameraAnalyzer(engine = get(), config = config)
    }

    single { TextPostProcessor() }
}