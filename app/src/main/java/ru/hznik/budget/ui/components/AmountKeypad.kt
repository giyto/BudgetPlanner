package ru.hznik.budget.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hznik.budget.R
import ru.hznik.budget.ui.theme.BudgetTheme

/** Цифровая клавиатура для ввода сумм по макету Add.dc.html: 1–9, запятая, 0, стереть. */
@Composable
fun AmountKeypad(onKey: (AmountKey) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    val keypadLabel = stringResource(R.string.editor_keypad)
    val commaLabel = stringResource(R.string.editor_key_comma)
    val backspaceLabel = stringResource(R.string.editor_key_backspace)
    val keys: List<AmountKey> = (1..9).map { AmountKey.Digit(it) } + listOf(AmountKey.Comma, AmountKey.Digit(0), AmountKey.Backspace)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.semantics { contentDescription = keypadLabel },
    ) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    val description = when (key) {
                        AmountKey.Comma -> commaLabel
                        AmountKey.Backspace -> backspaceLabel
                        is AmountKey.Digit -> key.value.toString()
                    }
                    Surface(
                        onClick = { onKey(key) },
                        shape = RoundedCornerShape(16.dp),
                        color = colors.card,
                        contentColor = colors.ink,
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .semantics { contentDescription = description },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (key) {
                                AmountKey.Backspace -> Icon(painterResource(R.drawable.ic_backspace), contentDescription = null)
                                AmountKey.Comma -> Text(",", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                                is AmountKey.Digit -> Text(key.value.toString(), fontSize = 24.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
