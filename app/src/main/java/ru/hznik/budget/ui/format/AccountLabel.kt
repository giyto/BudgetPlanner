package ru.hznik.budget.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.SupportedBanks
import ru.hznik.budget.domain.reconcile.ReconAccount

/** «Альфа *1234», «Т-Банк» или «Счёт без автоимпорта». */
fun accountLabel(account: ReconAccount, manualLabel: String): String {
    val bankId = account.bankId ?: return manualLabel
    val name = SupportedBanks.all.firstOrNull { it.id == bankId }?.name ?: bankId
    return account.cardLast4?.let { "$name *$it" } ?: name
}

@Composable
fun accountLabel(account: ReconAccount): String = accountLabel(account, stringResource(R.string.reconcile_account_manual))
