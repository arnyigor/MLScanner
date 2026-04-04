package com.arny.mlscanner.data.postprocessing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OcrErrorCorrectorTest {

    private lateinit var corrector: OcrErrorCorrector

    @BeforeEach
    fun setup() {
        corrector = OcrErrorCorrector()
    }

    @Test
    @DisplayName("Исправление смешанного алфавита: латиница в кириллическом слове")
    fun `fix latin chars in cyrillic word`() {
        val result = corrector.process("Мосkва — столица Pоссии", DocumentContext())
        assertEquals("Москва — столица России", result.text)
        assertTrue(result.changes.isNotEmpty())
    }

    @Test
    @DisplayName("Исправление кириллицы в латинском слове")
    fun `fix cyrillic chars in latin word`() {
        val result = corrector.process("Неllо Wоrld", DocumentContext())
        assertEquals("Hello World", result.text)
    }

    @Test
    @DisplayName("Цифра 0 внутри кириллического слова → О")
    fun `fix zero inside cyrillic word`() {
        val result = corrector.process("Д0кумент", DocumentContext())
        assertEquals("Документ", result.text)
    }

    @Test
    @DisplayName("Буква О внутри числа → 0")
    fun `fix O inside number`() {
        val result = corrector.process("Сумма: 1О00", DocumentContext())
        assertEquals("Сумма: 1000", result.text)
    }

    @Test
    @DisplayName("Не исправлять полностью латинский текст")
    fun `dont touch pure latin text`() {
        val result = corrector.process("Hello World", DocumentContext())
        assertEquals("Hello World", result.text)
    }

    @Test
    @DisplayName("Двойные пробелы → один")
    fun `fix double spaces`() {
        val result = corrector.process("Слово  два", DocumentContext())
        assertEquals("Слово два", result.text)
    }
}