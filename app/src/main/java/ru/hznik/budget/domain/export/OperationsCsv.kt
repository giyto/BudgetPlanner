package ru.hznik.budget.domain.export

import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.notifications.SupportedBanks
import java.time.format.DateTimeFormatter

/**
 * Операции в CSV для Excel и «Google Таблиц» (spec data-export «Экспорт операций в CSV», design.md решение 3):
 * UTF-8 с BOM, разделитель «;», сумма в рублях с запятой и знаком. Знак — обычный дефис-минус: таблица читает
 * «-450,00» как число, а типографский минус — как текст. Последние цифры карт в файл не попадают.
 */
object OperationsCsv {
    const val BOM = "﻿"
    private const val SEPARATOR = ";"
    private const val LINE_END = "\r\n"

    val HEADER = listOf("Дата", "Время", "Тип", "Сумма", "Категория", "Магазин или описание", "Комментарий", "Источник", "Банк", "Черновик")

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** Весь файл: метка BOM, заголовок и по строке на операцию в переданном порядке. */
    fun render(items: List<OperationItem>): String = buildString {
        append(BOM)
        append(HEADER.joinToString(SEPARATOR) { cell(it) }).append(LINE_END)
        items.forEach { append(row(it).joinToString(SEPARATOR)).append(LINE_END) }
    }

    /** Ячейки одной операции, уже подготовленные к записи. Дата и время — в часовом поясе операции. */
    fun row(item: OperationItem): List<String> {
        val op = item.operation
        return listOf(
            DATE.format(op.occurredAt),
            TIME.format(op.occurredAt),
            typeName(op.type),
            amount(op.amount.kopecks, outgoing = isOutgoing(op.type, op.originalType)),
            text(item.category.name),
            text(op.merchant.orEmpty()),
            text(op.comment.orEmpty()),
            sourceName(op.source),
            cell(op.bankId?.let { id -> SupportedBanks.all.firstOrNull { it.id == id }?.name ?: id }.orEmpty()),
            if (op.isDraft) "да" else "нет",
        )
    }

    /** Траты и переводы уменьшают деньги — со знаком «-»; у перевода себе знак по тому, каким он пришёл. */
    private fun isOutgoing(type: OperationType, original: OperationType?): Boolean =
        if (type == OperationType.OWN_TRANSFER) original != OperationType.INCOME else type.isOutgoing

    /** «1234,50» или «-450,00»: без пробелов между разрядами, чтобы таблица поняла число. */
    fun amount(kopecks: Long, outgoing: Boolean): String {
        val rubles = kopecks / 100
        val rest = (kopecks % 100).toString().padStart(2, '0')
        return (if (outgoing && kopecks != 0L) "-" else "") + "$rubles,$rest"
    }

    private fun typeName(type: OperationType) = when (type) {
        OperationType.EXPENSE -> "Трата"
        OperationType.INCOME -> "Доход"
        OperationType.REFUND -> "Возврат"
        OperationType.TRANSFER -> "Перевод"
        OperationType.OWN_TRANSFER -> "Перевод себе"
    }

    private fun sourceName(source: OperationSource) = when (source) {
        OperationSource.MANUAL -> "Вручную"
        OperationSource.NOTIFICATION -> "Уведомление банка"
        OperationSource.ADJUSTMENT -> "Корректировка"
    }

    /**
     * Текст пользователя или банка (категория, магазин, комментарий). Начало как у формулы — с апострофом, чтобы
     * таблица не выполнила его (spec data-export «Защита от формул в CSV»).
     */
    fun text(value: String): String {
        val safe = if (value.isNotEmpty() && value.first() in FORMULA_START) "'$value" else value
        return cell(safe)
    }

    /** Кавычки вокруг ячейки с разделителем, кавычкой или переносом строки; кавычки внутри удваиваются. */
    private fun cell(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private val FORMULA_START = setOf('=', '+', '-', '−', '@', '\t', '\r')
}
