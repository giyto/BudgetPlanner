package ru.hznik.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hznik.budget.ui.theme.BudgetTheme

/** Круглая подложка с иконкой, как на шагах онбординга. */
@Composable
fun Badge(icon: Painter, container: Color, content: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp).background(BudgetTheme.colors.card, CircleShape)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).background(container, RoundedCornerShape(20.dp))) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(34.dp))
        }
    }
}

/** Карточка с пунктами: иконка в плитке и текст, строки не ниже 52 dp; [dividers] — линии между пунктами. */
@Composable
fun PointsCard(
    points: List<Pair<Int, String>>,
    tileContainer: Color,
    tileContent: Color,
    modifier: Modifier = Modifier,
    dividers: Boolean = true,
) {
    val colors = BudgetTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        points.forEachIndexed { index, (icon, text) ->
            if (dividers && index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 52.dp).padding(vertical = 8.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).background(tileContainer, RoundedCornerShape(10.dp))) {
                    Icon(painterResource(icon), contentDescription = null, tint = tileContent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = colors.ink)
            }
        }
    }
}
