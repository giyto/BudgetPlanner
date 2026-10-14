package ru.hznik.budget.ui.home

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.usecase.IncomePeriodSuggestion
import ru.hznik.budget.domain.usecase.ObserveDrafts
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import javax.inject.Inject

const val DRAFTS_BANNER_TAG = "drafts_banner"
const val INCOME_SUGGESTION_TAG = "income_suggestion"

data class ImportBannersState(val drafts: Int = 0, val suggestedIncome: Money? = null)

/** Плашки автоимпорта на главной: черновики и предложение нового периода после зачисления (spec home-screen). */
@HiltViewModel
class ImportBannersViewModel @Inject constructor(
    observeDrafts: ObserveDrafts,
    private val suggestion: IncomePeriodSuggestion,
) : ViewModel() {
    val state: StateFlow<ImportBannersState> = combine(observeDrafts(), suggestion.observe()) { drafts, income ->
        ImportBannersState(drafts.size, income?.amount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportBannersState())

    fun dismissSuggestion() = viewModelScope.launch { suggestion.dismiss() }
}

/** «N операций ждут категорию / Из уведомлений банка» по макету Main. */
@Composable
fun DraftsBanner(count: Int, onOpen: () -> Unit) {
    if (count <= 0) return
    val colors = BudgetTheme.colors
    val title = stringResource(R.string.home_drafts, count, SpokenMoney.plural(count.toLong(), "операция ждёт", "операции ждут", "операций ждут"))
    val subtitle = stringResource(R.string.home_drafts_subtitle)
    val openLabel = stringResource(R.string.home_drafts_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Тег до clearAndSetSemantics: семантика после него (в том числе тег) стирается.
        modifier = Modifier
            .testTag(DRAFTS_BANNER_TAG)
            .fillMaxWidth()
            .background(colors.warnSoft, RoundedCornerShape(22.dp))
            .clickable(onClickLabel = openLabel, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = "$title. $subtitle"
                role = Role.Button
                onClick(label = openLabel) { onOpen(); true }
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).background(colors.card, RoundedCornerShape(14.dp))) {
            Icon(painterResource(R.drawable.ic_bell), contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(subtitle, fontSize = 13.sp, color = colors.muted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.ink)
    }
}

/** «Пришло N ₽ — начать новый период?» (сценарий 5.8). */
@Composable
fun IncomeSuggestionBanner(amount: Money, onStart: () -> Unit, onDismiss: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().background(colors.positiveSoft, RoundedCornerShape(18.dp)).padding(14.dp).testTag(INCOME_SUGGESTION_TAG),
    ) {
        Text(stringResource(R.string.home_income_suggestion, MoneyFormat.amount(amount.kopecks)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(stringResource(R.string.home_income_suggestion_text), fontSize = 13.sp, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.home_income_suggestion_start)) }
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.home_income_suggestion_later), color = colors.ink) }
        }
    }
}
