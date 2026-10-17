package ru.hznik.budget.ui.reconcile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.hznik.budget.R
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.usecase.ObserveMismatches
import ru.hznik.budget.ui.components.AlertBanner
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.format.accountLabel
import javax.inject.Inject
import kotlin.math.abs

const val MISMATCH_BANNER_TAG = "mismatch_banner"

private fun spokenBalance(kopecks: Long): String = (if (kopecks < 0) "минус " else "") + SpokenMoney.amount(kopecks)

/** Счета с расхождением для плашки на главной (spec home-screen «Плашка расхождения с банком»). */
@HiltViewModel
class MismatchBannerViewModel @Inject constructor(observeMismatches: ObserveMismatches) : ViewModel() {
    val mismatches: StateFlow<List<ReconState>> =
        observeMismatches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** «Расхождение с банком 450 ₽ / Банк: 12 300 ₽ · в приложении: 12 750 ₽» или «по 2 картам», по макетам MainMismatch и MainMismatchCards. */
@Composable
fun MismatchBanner(mismatches: List<ReconState>, onOpen: () -> Unit) {
    if (mismatches.isEmpty()) return
    val single = mismatches.singleOrNull()?.pending
    val title: String
    val subtitle: String
    val spoken: String
    if (single != null) {
        title = stringResource(R.string.reconcile_banner_one, MoneyFormat.amount(abs(single.diffKopecks)))
        subtitle = stringResource(R.string.reconcile_banner_one_subtitle, MoneyFormat.balance(single.bankKopecks), MoneyFormat.balance(single.expectedKopecks))
        spoken = "${stringResource(R.string.reconcile_banner_one, SpokenMoney.amount(abs(single.diffKopecks)))}. " +
            stringResource(R.string.reconcile_banner_one_subtitle, spokenBalance(single.bankKopecks), spokenBalance(single.expectedKopecks))
    } else {
        val n = mismatches.size.toLong()
        title = stringResource(R.string.reconcile_banner_many, mismatches.size, SpokenMoney.plural(n, "карте", "картам", "картам"))
        subtitle = mismatches.map { accountLabel(it.account) }.joinToString(" и ")
        spoken = "$title. $subtitle"
    }
    AlertBanner(
        icon = R.drawable.ic_alert,
        title = title,
        subtitle = subtitle,
        onClickLabel = stringResource(R.string.reconcile_banner_open),
        onClick = onOpen,
        modifier = Modifier.testTag(MISMATCH_BANNER_TAG),
        description = spoken,
    )
}
