package com.arny.mlscanner.domain.models.strings

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.arny.mlscanner.R

/**
 * Текст, который можно показать в UI.
 *
 * Интерфейс абстрагирует четыре распространённые формы предоставления текста:
 * 1. **Сырой текст** – удобно для строк из сети или ввода пользователя.
 * 2. **Идентификатор ресурса строки** – статический локализованный текст,
 *    объявленный в `res/values/strings.xml`.
 * 3. **Форматированная строка** – ресурс, содержащий спецификаторы формата
 *    (например, “Привет %s!”) и аргументы для их заполнения.
 * 4. **Плюральная строка** – ресурс с несколькими вариантами по количеству,
 *    из которых выбирается подходящий в зависимости от `quantity`.
 *
 * Все реализации неизменяемы, тип‑безопасны и могут использоваться как в Jetpack Compose,
 * так и в классических XML‑layout’ах.
 */
sealed interface StringHolder {

    /**
     * Обёртка идентификатора Android‑ресурса строки.
     *
     * Аннотация `@StringRes` гарантирует, что переданное число действительно
     * является ресурсом строки на этапе компиляции. Используйте этот тип,
     * когда текст статичен и уже объявлен в XML.
     *
     * @property id идентификатор ресурса строки из `res/values/strings.xml`.
     */
    @JvmInline
    value class Resource(@StringRes val id: Int) : StringHolder

    /**
     * Представляет произвольный текст, который может быть `null`.
     *
     * Полезен, когда строка приходит из динамических источников – пользовательского ввода,
     * ответа сервера и т.д. Нулевое значение позволяет явно выражать «нет текста».
     *
     * @property value необязательный строковый литерал (может быть `null`).
     */
    @JvmInline
    value class Text(val value: String?) : StringHolder

    /**
     * Представляет форматированную строку‑ресурс.
     *
     * Форматирование выполняется в момент рендеринга через `Resources.getString(id, args…)`.
     * Аргументы передаются так же, как и при вызове обычного `getString`.
     *
     * @property id        идентификатор ресурса строки с формат-спецификаторами.
     * @property formatArgs список аргументов, подставляемых в строку формата.
     */
    data class Formatted(
        @StringRes val id: Int,
        val formatArgs: List<Any>
    ) : StringHolder

    /**
     * Представляет плюральную (множественную) строку‑ресурс.
     *
     * Android выбирает подходящий вариант в зависимости от `quantity`.
     * Дополнительно можно передать аргументы для заполнения плейсхолдеров,
     * если они нужны. Если аргументов нет – оставьте список пустым.
     *
     * @property id         идентификатор ресурса плюральных строк (`R.plurals.*`).
     * @property quantity   количество, используемое при выборе подходящей формы.
     * @property formatArgs опциональный список аргументов для подстановки в выбранную строку.
     */
    data class Plural(
        @PluralsRes val id: Int,
        val quantity: Int,
        val formatArgs: List<Any> = emptyList()
    ) : StringHolder
}

/**
 * Преобразует [Throwable] в удобочитаемый [StringHolder].
 *
 * Если у исключения есть непустое сообщение, оно оборачивается в `StringHolder.Text`.
 * Иначе используется ресурс по умолчанию (`defaultRes`, по умолчанию – {@link R.string.system_error}).
 *
 * @receiver throwable, который возник
 * @param defaultRes идентификатор ресурса строки, используемый как запасной вариант,
 *                   если сообщение исключения пустое или отсутствует.
 * @return [StringHolder], подходящий для отображения в UI
 */
fun Throwable.toErrorHolder(
    @StringRes defaultRes: Int = R.string.system_error
): StringHolder =
    message?.takeUnless { it.isBlank() }
        ?.let { StringHolder.Text(it) }
        ?: StringHolder.Resource(defaultRes)

/**
 * Возвращает строку из [StringHolder] в виде обычного `String`.
 *
 * В зависимости от конкретной реализации `StringHolder` выполняется один из следующих вариантов:
 * 1. **Text** – возвращается сам текст, если он не‑null, иначе пустая строка.
 * 2. **Resource** – берётся локализованная строка по ID ресурса через `Context.getString()`.
 * 3. **Formatted** – форматируется строка‑ресурс с аргументами (`String.format`‑подобно).
 * 4. **Plural** – выбирается нужный вариант множественного числа и заполняются аргументы.
 *
 * Поскольку все варианты используют `Context`, рекомендуется передавать
 * приложение‑контекст (или любой контекст, который живёт дольше текущего UI‑компонента),
 * чтобы избежать утечек памяти.
 *
 * @receiver конкретный экземпляр [StringHolder]
 * @param context контекст для доступа к ресурсам приложения
 * @return строка, готовая к отображению в UI
 */
fun StringHolder.asString(context: Context): String = when (this) {
    is StringHolder.Text -> this.value.orEmpty()
    is StringHolder.Resource -> context.getString(this.id)
    is StringHolder.Formatted ->
        context.getString(this.id, *this.formatArgs.toTypedArray())

    is StringHolder.Plural ->
        context.resources.getQuantityString(
            this.id,
            this.quantity,
            *this.formatArgs.toTypedArray()
        )
}
