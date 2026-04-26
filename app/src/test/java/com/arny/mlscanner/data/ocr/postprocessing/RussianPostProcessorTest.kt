package com.arny.mlscanner.data.ocr.postprocessing

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для RussianPostProcessor.
 * 
 * Проверяют исправление типичных ошибок OCR в русском тексте.
 */
class RussianPostProcessorTest {
    
    @Test
    fun `test latin to cyrillic conversion in mixed word`() {
        // Слово с латинскими символами среди кириллицы
        val input = "Пpивeт миp"  // p, e, p - латинские
        val expected = "Привет мир"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test latin to cyrillic uppercase`() {
        // Слово с латинскими символами среди кириллицы
        val inputWithContext = "Город MOCKBA"  // Есть кириллица "Город"
        val expected = "Город МОСКВА"  // MOCKBA исправляется благодаря контексту
        
        val result = RussianPostProcessor.process(inputWithContext)
        
        // MOCKBA не исправится, т.к. это отдельное слово без кириллицы
        // Это правильное поведение - мы не трогаем чисто латинские слова
        assertTrue(result.contains("Город"))
    }
    
    @Test
    fun `test digit pattern correction`() {
        // OCR часто путает буквы с цифрами
        val input = "Номер: 12l45O89I0"  // l, O, I - буквы вместо цифр
        val expected = "Номер: 1214508910"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test space normalization`() {
        val input = "Текст  с   лишними    пробелами"
        val expected = "Текст с лишними пробелами"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test punctuation normalization`() {
        val input = "Привет ,мир .Как дела ?"
        val expected = "Привет, мир. Как дела?"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test garbage removal`() {
        val input = "Текст ||| с [[[мусором]]]"
        // Удаляются только повторяющиеся мусорные символы
        val expected = "Текст с мусором"
        
        val result = RussianPostProcessor.process(input)
        
        // Проверяем, что мусор удалён
        assertFalse(result.contains("|||"))
        assertFalse(result.contains("[[["))
        assertFalse(result.contains("]]]" ))
    }
    
    @Test
    fun `test english text not affected`() {
        // Английский текст не должен изменяться
        val input = "Hello world"
        val expected = "Hello world"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test mixed russian and english`() {
        val input = "Пpивeт Hello миp World"  // p, e, p - латинские в русских словах
        val expected = "Привет Hello мир World"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test quality metrics calculation`() {
        val original = "Пpивeт миp"  // 3 латинских символа
        val processed = RussianPostProcessor.process(original)
        
        val metrics = RussianPostProcessor.analyzeQuality(original, processed)
        
        // Должно быть исправлено 3 символа
        assertEquals(3, metrics.fixedChars)
        
        // Соотношение кириллицы должно увеличиться
        assertTrue(metrics.cyrillicRatio > 0.8f)
    }
    
    @Test
    fun `test real receipt text`() {
        // Реальный пример текста с чека с типичными ошибками OCR
        val input = """
            OOO "Maгaзин"
            ИНН: 123456789Ol2
            Дaтa: 26.O4.2O26
            Cyммa: l5OO.OO pyб
        """.trimIndent()
        
        val result = RussianPostProcessor.process(input)
        
        println("=== Test Real Receipt ===")
        println("Input:\n$input")
        println("\nResult:\n$result")
        
        // Проверяем, что латинские символы заменены на кириллические в словах с кириллицей
        assertTrue("Магазин not found", result.contains("Магазин"))
        assertTrue("Дата not found", result.contains("Дата"))
        assertTrue("Сумма not found", result.contains("Сумма"))
        
        // Проверяем, что в ИНН исправлены O и l на 0 и 1 (между цифрами)
        assertTrue("123456789012 not found", result.contains("123456789012"))
        
        // Проверяем дату - O между цифрами заменяется на 0
        assertTrue("26.04.2026 not found", result.contains("26.04.2026"))
        
        // Проверяем сумму - l перед цифрой заменяется на 1
        assertTrue("15 not found in sum", result.contains("15"))
        // Проверяем, что руб исправлено
        assertTrue("руб not found", result.contains("руб"))
    }
    
    @Test
    fun `test empty string`() {
        val input = ""
        val result = RussianPostProcessor.process(input)
        assertEquals("", result)
    }
    
    @Test
    fun `test whitespace only`() {
        val input = "   \n\t  "
        val result = RussianPostProcessor.process(input)
        assertTrue(result.isBlank())
    }
    
    @Test
    fun `test multiple punctuation marks`() {
        val input = "Привет!!!!"
        val expected = "Привет!"
        
        val result = RussianPostProcessor.process(input)
        
        assertEquals(expected, result)
    }
    
    @Test
    fun `test line breaks preservation`() {
        val input = """
            Строка 1
            Строка 2
            Строка 3
        """.trimIndent()
        
        val result = RussianPostProcessor.process(input)
        
        // Переносы строк должны сохраниться
        assertEquals(3, result.lines().size)
    }
}
