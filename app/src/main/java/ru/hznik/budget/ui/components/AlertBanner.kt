package ru.hznik.budget.ui.components

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import ru.hznik.budget.R
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles

/**
 * Плашка-предупреждение на главной по макету Main (.plaque): иконка на карточке, заголовок с подписью и [trailing]
 * справа (по умолчанию стрелка перехода). Для TalkBack плашка — одна кнопка с описанием [description] и действием
 * [onClickLabel]. Тег для тестов передаётся в [modifier]: он встаёт до clearAndSetSemantics, которая стирает
 * семантику после себя.
 */
@Composable
fun AlertBanner(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = "$title. $subtitle",
    trailing: @Composable () -> Unit = {
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = BudgetTheme.colors.ink)
    },
) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.warnSoft, RoundedCornerShape(22.dp))
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = onClickLabel) { onClick(); true }
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).background(colors.card, RoundedCornerShape(14.dp))) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextRoles.itemTitle, color = colors.ink)
            Text(subtitle, style = TextRoles.hint, color = colors.muted)
        }
        trailing()
    }
}
