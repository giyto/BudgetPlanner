package ru.hznik.budget.ui.format

import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.notifications.SupportedBanks

/** «Сбер *5678 → Альфа *1234» или «ИВАН П. → Альфа»: откуда и куда ушли деньги в переводе себе. */
fun ownTransferRoute(op: Operation, ownAccount: String): String {
    fun bank(id: String?, card: String?): String? {
        val name = id?.let { bankId -> SupportedBanks.all.firstOrNull { it.id == bankId }?.name } ?: return null
        return if (card != null) "$name *$card" else name
    }
    val here = bank(op.bankId, op.cardLast4) ?: ownAccount
    val there = bank(op.counterBankId, op.counterCardLast4) ?: op.merchant ?: ownAccount
    return if (op.originalType == OperationType.INCOME) "$there → $here" else "$here → $there"
}
