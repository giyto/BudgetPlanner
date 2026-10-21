package ru.hznik.budget.ui.stats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.DashedShape
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import ru.hznik.budget.domain.stats.DaySpending
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.LocalDate
import ru.hznik.budget.ui.theme.Motion
import ru.hznik.budget.ui.theme.LocalReducedMotion
import androidx.compose.animation.core.tween

/** Сколько дней видно без прокрутки (решение пользователя: 7 дней, остальные — пролистыванием). */
private const val VISIBLE_DAYS = 7.0

private val OverLimitKey = ExtraStore.Key<List<Boolean>>()
private val DatesKey = ExtraStore.Key<List<LocalDate>>()
private val LimitKey = ExtraStore.Key<Double>()

/**
 * Столбики трат по дням на Vico (design.md, решение 8): цвет по лимиту своего дня, пунктир — сегодняшний лимит,
 * при открытии видны последние 7 дней. Столбики на холсте TalkBack не читает, поэтому у диаграммы текстовое описание.
 */
@Composable
fun DailyChart(
    days: List<DaySpending>,
    todayLimitKopecks: Long?,
    limitLabel: String,
    /** День, подпись которого выделяется; `null` — без выделения (завершённый период). */
    today: LocalDate?,
    spoken: String,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    val reduced = LocalReducedMotion.current
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(days, todayLimitKopecks) {
        modelProducer.runTransaction {
            columnModel { series(days.map { it.spentKopecks / 100.0 }) }
            extras {
                it[OverLimitKey] = days.map { day -> day.isOverLimit }
                it[DatesKey] = days.map { day -> day.date }
                it[LimitKey] = (todayLimitKopecks ?: 0L) / 100.0
            }
        }
    }

    val columnShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    val withinColumn = remember(colors) { LineComponent(Fill(colors.chartBarOk), 30.dp, columnShape) }
    val overColumn = remember(colors) { LineComponent(Fill(colors.chartBarOver), 30.dp, columnShape) }
    val columnProvider = remember(withinColumn, overColumn) {
        object : ColumnCartesianLayer.ColumnProvider {
            override fun getColumn(entry: ColumnCartesianLayerModel.Entry, extraStore: ExtraStore): LineComponent =
                if (extraStore.getOrNull(OverLimitKey)?.getOrNull(entry.x.toInt()) == true) overColumn else withinColumn

            override fun getWidestSeriesColumn(seriesKey: Any, seriesIndex: Int, extraStore: ExtraStore): LineComponent = withinColumn
        }
    }
    // Ось Y не показывается; верх диаграммы — с запасом над самым высоким столбиком и линией лимита.
    val maxY = maxOf(days.maxOfOrNull { it.spentKopecks } ?: 0L, todayLimitKopecks ?: 0L) / 100.0
    val rangeProvider = remember(maxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = if (maxY > 0) maxY * 1.15 else 1.0) }

    val labelStyle = TextStyle(color = colors.muted, fontSize = 13.sp)
    val labelComponent = remember(colors) { TextComponent(labelStyle) }
    val todayStyle = SpanStyle(color = colors.ink, fontWeight = FontWeight.Bold)
    val labelFormatter = remember(today, todayStyle) {
        CartesianValueFormatter { context, x, _ ->
            val date = context.model.extraStore.getOrNull(DatesKey)?.getOrNull(x.toInt()) ?: return@CartesianValueFormatter " "
            val text = StatsFormat.dayLabel(date)
            if (date == today) buildAnnotatedString { withStyle(todayStyle) { append(text) } } else text
        }
    }

    val decorations = if (todayLimitKopecks != null) {
        listOf(
            remember(colors, todayLimitKopecks, limitLabel) {
                HorizontalLine(
                    y = { it.getOrNull(LimitKey) ?: 0.0 },
                    line = LineComponent(Fill(colors.chartLimitLine), 1.5.dp, DashedShape(dashLength = 5.dp, gapLength = 4.dp)),
                    labelComponent = TextComponent(TextStyle(color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)),
                    label = { limitLabel },
                    horizontalLabelPosition = Position.Horizontal.End,
                    verticalLabelPosition = Position.Vertical.Top,
                )
            },
        )
    } else {
        emptyList()
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(columnProvider = columnProvider, columnCollectionSpacing = 14.dp, rangeProvider = rangeProvider),
            bottomAxis = HorizontalAxis.rememberBottom(
                line = null,
                label = labelComponent,
                valueFormatter = labelFormatter,
                tick = null,
                guideline = null,
            ),
            decorations = decorations,
        ),
        modelProducer = modelProducer,
        modifier = modifier.height(200.dp).clearAndSetSemantics { contentDescription = spoken },
        scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
        zoomState = rememberVicoZoomState(
            zoomEnabled = false,
            // Всегда 7 мест под столбики: при меньшем числе дней столбики не расползаются на всю ширину.
            initialZoom = remember { Zoom.x(VISIBLE_DAYS) },
            minZoom = remember { Zoom.x(VISIBLE_DAYS) },
            maxZoom = remember { Zoom.x(VISIBLE_DAYS) },
        ),
        // Столбики вырастают при открытии и перестраиваются при смене периода (spec ui-motion «Графики статистики»).
        // initialAnimationSpec по умолчанию равен animationSpec: при уменьшенной анимации нет и начального появления.
        animationSpec = if (reduced) null else tween(Motion.CHART),
    )
}
