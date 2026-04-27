package com.arny.mlscanner.data.ocr.core

import android.util.Log

/**
 * Реестр OCR-движков.
 * Управляет доступными движками и их возможностями.
 */
class OcrEngineRegistry {
    
    companion object {
        private const val TAG = "OcrEngineRegistry"
    }
    
    private val engines = mutableMapOf<String, IOcrEngine>()
    private val states = mutableMapOf<String, OcrEngineState>()
    
    /**
     * Регистрирует движок.
     */
    fun register(engine: IOcrEngine) {
        engines[engine.id] = engine
        states[engine.id] = OcrEngineState.NotInitialized
        Log.d(TAG, "Registered engine: ${engine.id} (${engine.name})")
    }
    
    /**
     * Получить движок по ID.
     */
    fun getEngine(engineId: String): IOcrEngine? {
        return engines[engineId]
    }
    
    /**
     * Получить все зарегистрированные движки.
     */
    fun getAllEngines(): List<IOcrEngine> {
        return engines.values.toList()
    }
    
    /**
     * Получить готовые движки.
     */
    fun getReadyEngines(): List<IOcrEngine> {
        return engines.values.filter { it.isReady() }
    }
    
    /**
     * Получить движки, поддерживающие задачу.
     */
    fun getEnginesForTask(taskType: OcrTaskType): List<IOcrEngine> {
        return engines.values.filter { engine ->
            engine.isReady() && engine.capabilities.supportedTasks.contains(taskType)
        }
    }
    
    /**
     * Получить движки, поддерживающие язык.
     */
    fun getEnginesForLanguage(language: String): List<IOcrEngine> {
        return engines.values.filter { engine ->
            engine.isReady() && engine.capabilities.supportedLanguages.contains(language)
        }
    }
    
    /**
     * Получить самый быстрый движок.
     */
    fun getFastestEngine(): IOcrEngine? {
        return getReadyEngines()
            .minByOrNull { it.capabilities.speedTier.ordinal }
    }
    
    /**
     * Получить самый точный движок.
     */
    fun getMostAccurateEngine(): IOcrEngine? {
        return getReadyEngines()
            .maxByOrNull { it.capabilities.accuracyTier.ordinal }
    }
    
    /**
     * Получить состояние движка.
     */
    fun getState(engineId: String): OcrEngineState {
        return states[engineId] ?: OcrEngineState.NotInitialized
    }
    
    /**
     * Обновить состояние движка.
     */
    fun updateState(engineId: String, state: OcrEngineState) {
        states[engineId] = state
    }
    
    /**
     * Инициализировать все движки.
     */
    suspend fun initializeAll(): Map<String, OcrEngineState> {
        val results = mutableMapOf<String, OcrEngineState>()
        
        for ((id, engine) in engines) {
            try {
                Log.d(TAG, "Initializing $id...")
                val state = engine.initialize()
                states[id] = state
                results[id] = state
                
                when (state) {
                    is OcrEngineState.Ready -> 
                        Log.i(TAG, "$id initialized successfully")
                    is OcrEngineState.Error -> 
                        Log.w(TAG, "$id initialization failed: ${state.message}")
                    is OcrEngineState.Unavailable -> 
                        Log.w(TAG, "$id is unavailable on this device")
                    else -> {}
                }
            } catch (e: Exception) {
                val errorState = OcrEngineState.Error("Initialization failed", e)
                states[id] = errorState
                results[id] = errorState
                Log.e(TAG, "$id initialization error", e)
            }
        }
        
        return results
    }
    
    /**
     * Освободить все движки.
     */
    fun releaseAll() {
        engines.values.forEach { engine ->
            try {
                engine.release()
                states[engine.id] = OcrEngineState.NotInitialized
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing ${engine.id}", e)
            }
        }
        Log.d(TAG, "All engines released")
    }
    
    /**
     * Получить статистику по движкам.
     */
    fun getStats(): EngineStats {
        val total = engines.size
        val ready = engines.values.count { it.isReady() }
        val offline = engines.values.count { it.capabilities.isOffline }
        val experimental = engines.values.count { it.capabilities.isExperimental }
        
        return EngineStats(
            total = total,
            ready = ready,
            offline = offline,
            experimental = experimental
        )
    }
    
    data class EngineStats(
        val total: Int,
        val ready: Int,
        val offline: Int,
        val experimental: Int
    )
}
