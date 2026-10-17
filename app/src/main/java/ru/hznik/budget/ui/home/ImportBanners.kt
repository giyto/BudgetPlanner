package ru.hznik.budget.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import ru.hznik.budget.ui.components.AlertBanner
import ru.hznik.budget.ui.components.ButtonSize
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.SecondaryButton
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import javax.inject.Inject
import ru.hznik.budget.ui.launchSave

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

    fun dismissSuggestion() = launchSave { suggestion.dismiss() }
}

/** «N операций ждут категорию / Из уведомлений банка» по макету Main. */
@Composable
fun DraftsBanner(count: Int, onOpen: () -> Unit) {
    if (count <= 0) return
    AlertBanner(
        icon = R.drawable.ic_bell,
        title = stringResource(R.string.home_drafts, count, SpokenMoney.plural(count.toLong(), "операция ждёт", "операции ждут", "операций ждут")),
        subtitle = stringResource(R.string.home_drafts_subtitle),
        onClickLabel = stringResource(R.string.home_drafts_open),
        onClick = onOpen,
        modifier = Modifier.testTag(DRAFTS_BANNER_TAG),
    )
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
        Text(stringResource(R.string.home_income_suggestion_text), style = TextRoles.hint, color = colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.home_income_suggestion_start), onStart, Modifier.weight(1f), size = ButtonSize.Compact)
            SecondaryButton(stringResource(R.string.home_income_suggestion_later), onDismiss, Modifier.weight(1f), size = ButtonSize.Compact)
        }
    }
}
