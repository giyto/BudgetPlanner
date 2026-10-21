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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.hznik.budget.R
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import ru.hznik.budget.ui.theme.LocalReducedMotion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.CircularProgressIndicator

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
 * Главная кнопка по макетам (.btn): синяя, на всю ширину, не ниже высоты [size]. Недоступная — серая плашка
 * с приглушённым текстом (.btn.off), смена плавная. [danger] — необратимое действие (удаление данных): кнопка цвета
 * перерасхода. [icon] стоит перед текстом с отступом 10 dp, как в макете. [busy] — действие выполняется: вместо
 * текста крутится индикатор, нажатия не принимаются; при выключенных анимациях вместо индикатора [busyText]
 * (spec ui-motion «Анимации экранов данных»).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Large,
    danger: Boolean = false,
    busy: Boolean = false,
    busyText: String = text,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = BudgetTheme.colors
    val container = animatedColor(
        when {
            danger -> colors.warn
            enabled || busy -> colors.primary
            else -> colors.card2
        },
    )
    val content = animatedColor(
        when {
            danger -> if (colors.isDark) colors.background else Color.White
            enabled || busy -> colors.onPrimary
            else -> colors.muted
        },
    )
    Button(
        onClick = { if (!busy) onClick() },
        enabled = enabled || busy,
        shape = RoundedCornerShape(size.corner),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = size.minHeight),
    ) {
        when {
            busy && LocalReducedMotion.current -> Text(busyText, style = size.textStyle, textAlign = TextAlign.Center)
            busy -> CircularProgressIndicator(
                color = content,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(22.dp).semantics { contentDescription = busyText },
            )
            else -> {
                if (icon != null) {
                    icon()
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, style = size.textStyle, textAlign = TextAlign.Center)
            }
        }
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
        Text(text, style = size.textStyle, textAlign = TextAlign.Center)
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
