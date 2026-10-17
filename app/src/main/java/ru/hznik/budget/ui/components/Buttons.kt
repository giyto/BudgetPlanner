package ru.hznik.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.hznik.budget.R
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles

/** Размер кнопки по макетам: наименьшая высота, скругление и текст. */
enum class ButtonSize(internal val minHeight: Dp, internal val corner: Dp, internal val textStyle: TextStyle) {
    /** Главное действие внизу экрана (.btn): 56 dp, текст 17 sp. */
    Large(56.dp, 18.dp, TextRoles.buttonLarge),

    /** Второстепенная кнопка (.btn2) и главная кнопка в нижнем листе: 52 dp, текст 16 sp. */
    Medium(52.dp, 18.dp, TextRoles.button),

    /** Кнопка внутри плашки на главной: 48 dp, скругление 14 dp, текст 16 sp. */
    Compact(48.dp, 14.dp, TextRoles.button),
}

/**
 * Главная кнопка по макетам (.btn): синяя, на всю ширину, не ниже высоты [size]. [danger] — необратимое действие
 * (удаление данных): кнопка цвета перерасхода. [icon] стоит перед текстом с отступом 10 dp, как в макете.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Large,
    danger: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = BudgetTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(size.corner),
        colors = if (danger) {
            ButtonDefaults.buttonColors(containerColor = colors.warn, contentColor = if (colors.isDark) colors.background else Color.White)
        } else {
            ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
        },
        modifier = modifier.fillMaxWidth().heightIn(min = size.minHeight),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = size.textStyle)
    }
}

/**
 * Второстепенная кнопка с рамкой по макетам (.btn2): рамка цвета чипа, на всю ширину, не ниже высоты [size].
 * [contentColor] — цвет текста и иконки; [icon] стоит перед текстом с отступом 8 dp.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Medium,
    contentColor: Color = BudgetTheme.colors.ink,
    icon: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(size.corner),
        border = BorderStroke(1.dp, BudgetTheme.colors.chipBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        modifier = modifier.fillMaxWidth().heightIn(min = size.minHeight),
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = size.textStyle)
    }
}

/** Кнопка «+» новой операции в правом нижнем углу главной и списка операций; место в углу задаёт [modifier]. */
@Composable
fun AddOperationFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        containerColor = colors.primary,
        contentColor = colors.onPrimary,
        modifier = modifier.padding(end = 20.dp, bottom = 20.dp).size(60.dp),
    ) {
        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_add_operation), Modifier.size(26.dp))
    }
}
