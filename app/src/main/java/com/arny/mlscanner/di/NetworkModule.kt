// File: di/networkModule.kt
package com.arny.mlscanner.di

import kotlinx.serialization.json.Json
import org.koin.dsl.module

val networkModule = module {

    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

//    // Ktor Client
//    single {
//        HttpClient(OkHttp) {
//            install(ContentNegotiation) {
//                json(Json {
//                    prettyPrint = true
//                    isLenient = true
//                    ignoreUnknownKeys = true
//                })
//            }
//
//            install(HttpTimeout) {
//                requestTimeoutMillis = 15_000
//                connectTimeoutMillis = 15_000
//                socketTimeoutMillis = 15_000
//            }
//
//            install(Logging) {
//                logger = object : Logger {
//                    override fun log(message: String) {
//                        Log.v("Ktor", message)
//                    }
//                }
//                level = LogLevel.INFO
//            }
//
//            defaultRequest {
//                contentType(ContentType.Application.Json)
//            }
//        }
//    }
}
