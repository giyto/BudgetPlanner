package ru.hznik.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.usecase.PaymentDraft
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RU)

/** «18 октября». */
fun formatDayMonth(date: LocalDate): String = DAY_MONTH.format(date)

/**
 * Календарь Material 3 с ограничением дат: [isAllowed] решает, какие дни можно выбрать.
 * DatePicker работает с полуночью UTC выбранного дня.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetDatePicker(
    initial: LocalDate?,
    title: String,
    isAllowed: (LocalDate) -> Boolean,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val selectable = remember(isAllowed) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = isAllowed(utcMillisToDate(utcTimeMillis))
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onSelect(utcMillisToDate(it)) } ?: onDismiss() }) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state, title = { Text(title, Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

private fun utcMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** Кнопка-чип с иконкой: дата, категория и подобный выбор (как чипы даты и комментария на экране ввода). */
@Composable
fun ChoiceChip(icon: Int?, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = colors.card,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.chipBorder),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Переключатель из нескольких вариантов в стиле макета (как «Трата / Доход / Возврат»). */
@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(colors.segmentTrack, RoundedCornerShape(24.dp))
            .padding(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                selected = isSelected,
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) colors.segmentOn else colors.segmentTrack,
                contentColor = colors.ink,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
            }
        }
    }
}

/** Сумма, введённая текстом («650», «650,5»), в копейках; `null`, если это не сумма в допустимом диапазоне. */
fun parseRubles(text: String): Long? {
    val normalized = text.trim().replace(" ", "").replace(' '.toString(), "").replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toBigDecimalOrNull() ?: return null
    if (value.scale() > 2 || value.signum() <= 0) return null
    val kopecks = value.movePointRight(2).toLong()
    return kopecks.takeIf { Money.ofKopecksOrNull(it) != null }
}

/** Состояние формы обязательного платежа. */
data class PaymentFormState(
    val name: String = "",
    val amountText: String = "",
    val dueDate: LocalDate,
    val repeat: PaymentRepeat = PaymentRepeat.MONTHLY,
    val categoryId: Long? = null,
    val showErrors: Boolean = false,
) {
    val nameValid: Boolean get() = name.trim().length in 1..PlannedPayment.MAX_NAME_LENGTH
    val amountKopecks: Long? get() = parseRubles(amountText)

    /** Готовый черновик или `null`, если форма заполнена не до конца. */
    fun toDraft(defaultCategoryId: Long): PaymentDraft? {
        val kopecks = amountKopecks ?: return null
        if (!nameValid) return null
        return PaymentDraft(name.trim(), Money(kopecks), dueDate, repeat, categoryId ?: defaultCategoryId)
    }

    companion object {
        fun from(payment: PlannedPayment) = PaymentFormState(
            name = payment.name,
            amountText = AmountInput.fromKopecks(payment.amount.kopecks),
            dueDate = payment.dueDate,
            repeat = payment.repeat,
            categoryId = payment.categoryId,
        )
    }
}

/** Поля обязательного платежа: название, сумма, дата, повтор, категория. */
@Composable
fun PaymentForm(
    state: PaymentFormState,
    categories: List<Category>,
    defaultCategoryId: Long?,
    onChange: (PaymentFormState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    val category = categories.firstOrNull { it.id == (state.categoryId ?: defaultCategoryId) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        OutlinedTextField(
            value = state.name,
            onValueChange = { onChange(state.copy(name = it.take(PlannedPayment.MAX_NAME_LENGTH))) },
            label = { Text(stringResource(R.string.payment_name)) },
            placeholder = { Text(stringResource(R.string.payment_name_hint)) },
            singleLine = true,
            isError = state.showErrors && !state.nameValid,
            supportingText = if (state.showErrors && !state.nameValid) ({ Text(stringResource(R.string.payment_error_name)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.amountText,
            onValueChange = { onChange(state.copy(amountText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(10))) },
            label = { Text(stringResource(R.string.payment_amount)) },
            singleLine = true,
            isError = state.showErrors && state.amountKopecks == null,
            supportingText = if (state.showErrors && state.amountKopecks == null) ({ Text(stringResource(R.string.payment_error_amount)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(R.drawable.ic_calendar, formatDayMonth(state.dueDate), { showDate = true }, Modifier.weight(1f))
            ChoiceChip(null, category?.name ?: stringResource(R.string.payment_category), { showCategories = true }, Modifier.weight(1f))
        }
        SegmentedChoice(
            options = listOf(PaymentRepeat.MONTHLY to stringResource(R.string.payment_repeat_monthly), PaymentRepeat.ONCE to stringResource(R.string.payment_repeat_once)),
            selected = state.repeat,
            onSelect = { onChange(state.copy(repeat = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showDate) {
        BudgetDatePicker(state.dueDate, stringResource(R.string.payment_date), isAllowed = { true }, onDismiss = { showDate = false }) {
            onChange(state.copy(dueDate = it))
            showDate = false
        }
    }
    if (showCategories) {
        CategoryPickerSheet(categories, state.categoryId ?: defaultCategoryId, onDismiss = { showCategories = false }) {
            onChange(state.copy(categoryId = it))
            showCategories = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<Category>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    onSelect: (Long) -> Unit,
) {
    val colors = BudgetTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        LazyColumn(modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            if (title != null) {
                item(key = "title") {
                    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            items(categories, key = { it.id }) { category ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { onSelect(category.id) }
                        .padding(horizontal = 20.dp),
                ) {
                    CategoryTile(category, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(category.name, fontSize = 15.sp, color = colors.ink, modifier = Modifier.weight(1f))
                    if (category.id == selectedId) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.primaryText)
                }
            }
        }
    }
}
