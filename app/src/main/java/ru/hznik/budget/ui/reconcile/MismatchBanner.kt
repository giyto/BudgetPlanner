package ru.hznik.budget.ui.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.hznik.budget.R
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.usecase.ObserveMismatches
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.format.accountLabel
import ru.hznik.budget.ui.theme.BudgetTheme
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
    val colors = BudgetTheme.colors
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
    val openLabel = stringResource(R.string.reconcile_banner_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Тег до clearAndSetSemantics: семантика после него (в том числе тег) стирается.
        modifier = Modifier
            .testTag(MISMATCH_BANNER_TAG)
            .fillMaxWidth()
            .background(colors.warnSoft, RoundedCornerShape(22.dp))
            .clickable(onClickLabel = openLabel, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = openLabel) { onOpen(); true }
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).background(colors.card, RoundedCornerShape(14.dp))) {
            Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(subtitle, fontSize = 13.sp, color = colors.muted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.ink)
    }
}
