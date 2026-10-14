package ru.hznik.budget.domain.model

/** Сумма операции в копейках. Только положительная: направление задаёт тип операции. */
@JvmInline
value class Money(val kopecks: Long) {
    init {
        require(kopecks in MIN_KOPECKS..MAX_KOPECKS) { "Сумма вне диапазона: $kopecks коп." }
    }

    /** Целая часть в рублях. */
    val rubles: Long get() = kopecks / 100

    /** Копейки после запятой, 0..99. */
    val kopecksPart: Int get() = (kopecks % 100).toInt()

    companion object {
        const val MIN_KOPECKS = 1L
        const val MAX_KOPECKS = 999_999_999L

        /** Деньги из копеек или `null`, если сумма вне диапазона (например, 0). */
        fun ofKopecksOrNull(kopecks: Long): Money? =
            if (kopecks in MIN_KOPECKS..MAX_KOPECKS) Money(kopecks) else null
    }
}
