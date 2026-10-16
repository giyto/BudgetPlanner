package ru.hznik.budget.domain.categorize

/**
 * Ключ магазина для правил категорий (design.md add-auto-categorization, решение 1): нижний регистр, «ё» как «е»,
 * без кавычек и знаков, без слов с цифрами (номер терминала, точки, карты). «PYATEROCHKA 1234» и «Pyaterochka 5678»
 * дают один ключ. В отличие от поиска по операциям, где номер терминала полезен, здесь он мешает.
 */
object MerchantKey {
    private val SEPARATORS = Regex("""[«»"'„“”*.,;:!?()\[\]/\\|_#№+=-]+""")
    private val SPACES = Regex("""\s+""")

    /** Ключ или `null`, если после нормализации ничего не осталось (например, «796*1234»). */
    fun of(name: String?): String? {
        if (name == null) return null
        val words = name.lowercase()
            .replace('ё', 'е')
            .replace(SEPARATORS, " ")
            .split(SPACES)
            .filter { word -> word.isNotEmpty() && word.none(Char::isDigit) }
        return words.joinToString(" ").ifEmpty { null }
    }
}
