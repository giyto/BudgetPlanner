package ru.hznik.budget.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hznik.budget.R
import ru.hznik.budget.domain.stats.CategoryBreakdown
import ru.hznik.budget.domain.stats.CategorySlice
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.chartColor
import kotlin.math.atan2
import kotlin.math.hypot
import ru.hznik.budget.ui.theme.motionTween
import ru.hznik.budget.ui.theme.Motion
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.components.animatedColor
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect

private val DONUT_SIZE = 120.dp
private val DONUT_STROKE = 18.dp

/** Насколько можно промахнуться мимо кольца пальцем. */
private val TOUCH_SLOP = 14.dp

/** Зазор между долями кольца, в градусах. */
private const val SLICE_GAP = 2f

/** Невыбранные доли при выборе одной; выбранная толще на [SELECTED_EXTRA]. */
private const val DIMMED_ALPHA = 0.25f
private val SELECTED_EXTRA = 6.dp

/** Сколько строк списка видно до «Показать все». */
private const val COLLAPSED_ROWS = 5

/** Выбор доли кольца: id категории или «Остальное». */
private const val REST_KEY = -1L

/** Тег кольца для UI-тестов: по долям кольца TalkBack и тесты отдельно не ходят. */
const val STATS_DONUT_TAG = "stats_donut"

/** Номер доли под углом [angleFromTop] (градусы по часовой стрелке от верха) для долей с суммами [values]. */
internal fun sliceAt(angleFromTop: Float, values: List<Long>): Int? {
    val total = values.sum()
    if (total <= 0) return null
    var start = 0f
    values.forEachIndexed { index, value ->
        val end = start + value * 360f / total
        if (angleFromTop >= start && angleFromTop < end) return index
        start = end
    }
    return values.lastIndex
}

/**
 * Карточка трат по категориям (spec statistics): кольцо на `Canvas` с суммой рядом и список всех категорий под ним.
 * Нажатие на долю кольца подсвечивает её строку, «Остальное» раскрывает список; нажатие на строку открывает операции категории.
 */
@Composable
fun CategoryCard(range: DateRange, breakdown: CategoryBreakdown, onOpenCategory: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    val rest = stringResource(R.string.stats_rest)
    val noSpending = stringResource(R.string.stats_no_spending)
    var selected by rememberSaveable(range) { mutableStateOf<Long?>(null) }
    var expanded by rememberSaveable(range) { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current

    Column(
        modifier = modifier
            .background(colors.card, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        if (breakdown.isEmpty) {
            Text(
                noSpending,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp).clearAndSetSemantics { contentDescription = noSpending },
            )
            return@Column
        }
        val sliceColors = breakdown.slices.map { it.category?.let { c -> colors.chartColor(c.color) } ?: colors.chartOther }
        val sliceKeys = breakdown.slices.map { it.category?.id ?: REST_KEY }
        val selectedSlice = breakdown.slices.getOrNull(sliceKeys.indexOf(selected))
        val spoken = "Траты ${StatsFormat.spokenRange(range)}: ${SpokenMoney.amount(breakdown.totalKopecks)}. " +
            breakdown.slices.joinToString(". ") { "${it.category?.name ?: rest}: ${spokenPercent(it.percent)}, ${SpokenMoney.amount(it.kopecks)}" }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // Тег до clearAndSetSemantics: семантика после него (в том числе тег) стирается.
            modifier = Modifier.padding(horizontal = 4.dp).testTag(STATS_DONUT_TAG).clearAndSetSemantics {
                contentDescription = spoken
                // Выбранная доля — то же, что текст справа от кольца.
                if (selectedSlice != null) {
                    stateDescription = "Выбрано: ${selectedSlice.category?.name ?: rest}, ${SpokenMoney.amount(selectedSlice.kopecks)}, ${spokenPercent(selectedSlice.percent)}"
                }
            },
        ) {
            Donut(
                slices = breakdown.slices,
                sliceColors = sliceColors,
                selectedIndex = sliceKeys.indexOf(selected).takeIf { it >= 0 },
                onSliceTap = { index ->
                    val key = sliceKeys[index]
                    selected = if (selected == key) null else key
                    if (key == REST_KEY && selected == REST_KEY) expanded = true
                },
                modifier = Modifier.size(DONUT_SIZE),
            )
            // Текст справа от кольца плавно сменяется при выборе доли (spec ui-motion «Графики статистики»).
            AnimatedContent(
                targetState = selected.takeIf { selectedSlice != null },
                transitionSpec = {
                    val change = if (reduced) EnterTransition.None togetherWith ExitTransition.None else fadeIn(tween(Motion.ITEM)) togetherWith fadeOut(tween(Motion.ITEM))
                    change.using(SizeTransform { _, _ -> if (reduced) snap() else tween(Motion.ITEM) })
                },
                contentAlignment = Alignment.CenterStart,
                label = "donut side",
                modifier = Modifier.weight(1f),
            ) { shownKey ->
            val shownSlice = breakdown.slices.getOrNull(sliceKeys.indexOf(shownKey))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (shownSlice == null) {
                    Text(StatsFormat.range(range), fontSize = 13.sp, color = colors.muted, maxLines = 1)
                    Amount(breakdown.totalKopecks)
                    Text(categoriesCount(breakdown.all.size), fontSize = 13.sp, color = colors.muted)
                } else {
                    Text(shownSlice.category?.name ?: rest, fontSize = 13.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Amount(shownSlice.kopecks)
                    val details = if (shownSlice.category == null) {
                        "${categoriesCount(breakdown.hidden.size)} · ${shownSlice.percent}%"
                    } else {
                        "${shownSlice.percent}%"
                    }
                    Text(details, fontSize = 13.sp, color = colors.muted)
                }
            }
            }
        }

        // Подсветка — от доли, которая сейчас есть в кольце: категория могла уйти в «Остальное» после правки операций.
        val highlighted: Set<Long> = when {
            selectedSlice == null -> emptySet()
            selectedSlice.category == null -> breakdown.hidden.mapNotNull { it.category?.id }.toSet()
            else -> setOf(selectedSlice.category.id)
        }
        val rows = if (expanded) breakdown.all else breakdown.all.take(COLLAPSED_ROWS)
        Column(modifier = Modifier.padding(top = 10.dp)) {
            rows.forEach { slice ->
                val category = slice.category ?: return@forEach
                CategoryRow(slice, colors.chartColor(category.color), category.id in highlighted) { onOpenCategory(category.id) }
            }
        }
        if (breakdown.all.size > COLLAPSED_ROWS) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 44.dp)) {
                Text(
                    if (expanded) stringResource(R.string.stats_collapse) else stringResource(R.string.stats_show_all, breakdown.all.size),
                    color = colors.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Amount(kopecks: Long) {
    Text(
        MoneyFormat.amount(kopecks),
        style = MaterialTheme.typography.titleLarge,
        fontSize = 22.sp,
        color = BudgetTheme.colors.ink,
        maxLines = 1,
    )
}

private fun categoriesCount(n: Int) = "$n ${SpokenMoney.plural(n.toLong(), "категория", "категории", "категорий")}"

private fun spokenPercent(percent: Int) = "$percent ${SpokenMoney.plural(percent.toLong(), "процент", "процента", "процентов")}"

@Composable
private fun Donut(
    slices: List<CategorySlice>,
    sliceColors: List<Color>,
    selectedIndex: Int?,
    onSliceTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val values = slices.map { it.kopecks }
    // Обработчик меняется вместе с набором категорий, а pointerInput перезапускается только при новых суммах.
    val onTap by rememberUpdatedState(onSliceTap)
    // Кольцо дорисовывается по кругу от верхней точки при открытии и при смене долей (design.md add-animations, решение 7).
    val reduced = LocalReducedMotion.current
    val sweepProgress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(values) {
        if (reduced) {
            sweepProgress.snapTo(1f)
        } else {
            sweepProgress.snapTo(0f)
            sweepProgress.animateTo(1f, tween(Motion.CHART, easing = FastOutSlowInEasing))
        }
    }
    // Выбор доли: остальные плавно бледнеют, выбранная плавно утолщается.
    val alphas = slices.indices.map { index ->
        key(index) {
            animateFloatAsState(if (selectedIndex == null || selectedIndex == index) 1f else DIMMED_ALPHA, motionTween(Motion.ITEM), label = "slice alpha").value
        }
    }
    val extraWidths = slices.indices.map { index ->
        key(index) {
            animateDpAsState(if (selectedIndex == index) SELECTED_EXTRA else 0.dp, motionTween(Motion.ITEM), label = "slice width").value
        }
    }
    Canvas(
        modifier.pointerInput(values) {
            detectTapGestures { offset ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val distance = hypot(offset.x - center.x, offset.y - center.y)
                val outer = size.width / 2f
                val inner = outer - DONUT_STROKE.toPx()
                val slop = TOUCH_SLOP.toPx()
                if (distance < inner - slop || distance > outer + slop) return@detectTapGestures
                val angle = (Math.toDegrees(atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())).toFloat() + 90f + 360f) % 360f
                sliceAt(angle, values)?.let(onTap)
            }
        },
    ) {
        val stroke = DONUT_STROKE.toPx()
        val total = values.sum().toFloat()
        val gap = if (slices.size > 1) SLICE_GAP else 0f
        val drawn = sweepProgress.value * 360f
        var start = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = slice.kopecks / total * 360f
            val width = stroke + extraWidths[index].toPx()
            val inset = stroke / 2
            // Пока кольцо дорисовывается, видна только часть доли до текущего угла.
            // После развёртки — как раньше: каждая доля не меньше 0,5°.
            val full = (sweep - gap).coerceAtLeast(0.5f)
            val visible = if (sweepProgress.value < 1f) full.coerceAtMost(drawn - (start + 90f) - gap / 2) else full
            if (visible <= 0f) {
                start += sweep
                return@forEachIndexed
            }
            drawArc(
                color = sliceColors[index].copy(alpha = alphas[index]),
                startAngle = start + gap / 2,
                sweepAngle = visible,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = width),
            )
            start += sweep
        }
    }
}

@Composable
private fun CategoryRow(slice: CategorySlice, color: Color, highlighted: Boolean, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    val name = slice.category?.name.orEmpty()
    val openLabel = stringResource(R.string.stats_open_operations)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = animatedColor(if (highlighted) colors.background else colors.card),
        contentColor = colors.ink,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        // Описание на содержимом, а не на Surface: иначе вместе с текстами пропало бы и действие нажатия.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 10.dp)
                .clearAndSetSemantics {
                    contentDescription = "$name: ${SpokenMoney.amount(slice.kopecks)}, ${spokenPercent(slice.percent)}"
                    onClick(label = openLabel) { onClick(); true }
                },
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(name, fontSize = 15.sp, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(MoneyFormat.amount(slice.kopecks), fontSize = 15.sp, color = colors.ink)
            Text("${slice.percent}%", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.padding(start = 4.dp))
        }
    }
}
