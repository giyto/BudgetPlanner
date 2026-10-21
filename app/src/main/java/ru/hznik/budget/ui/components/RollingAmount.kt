package ru.hznik.budget.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.flow.first
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.theme.Motion
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints

/**
 * Разбор суммы на разряды для прокрутки цифр (spec ui-motion, design.md add-animations, решение 3). Символы
 * выравниваются **справа**: при смене длины числа (1 000 → 950) разряды не сдвигаются. Чистая функция — проверяется
 * unit-тестом.
 */
object RollingText {
    /** Разряд суммы: [key] — номер символа справа, [rolls] — меняется цифра, её нужно прокрутить. */
    data class Column(val key: Int, val old: Char?, val new: Char?) {
        val rolls: Boolean get() = old != new && (old?.isDigit() == true || new?.isDigit() == true)
    }

    /** Разряды старой и новой строки, слева направо; `null` — символа в этой строке нет. */
    fun columns(old: String?, new: String): List<Column> {
        val previous = old ?: new
        val length = maxOf(previous.length, new.length)
        return (length - 1 downTo 0).map { key ->
            Column(key, previous.getOrNull(previous.length - 1 - key), new.getOrNull(new.length - 1 - key))
        }
    }
}

private fun Char?.isDigitChar() = this?.isDigit() == true

/**
 * Сумма, у которой изменившиеся цифры прокручиваются, как на табло: при уменьшении новая цифра въезжает сверху,
 * при росте — снизу; пробелы, «₽», «−» стоят на месте, лишний разряд уходит вместе с прокруткой. TalkBack и тесты
 * видят сумму целиком и сразу итоговой, без промежуточных цифр. При первом показе и при выключенных в системе
 * анимациях сумма показывается сразу.
 *
 * Показанная сумма сохраняется вместе с экраном: если сумма изменилась, пока открыт другой экран (сохранили трату
 * и вернулись), цифры докручиваются от прежнего значения.
 */
@Composable
fun RollingAmount(text: String, value: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    // Строка, от которой идёт прокрутка; становится текущей, когда все разряды доехали.
    var shownText by rememberSavedInProcess { text }
    var lastValue by rememberSavedInProcess { value }
    var lastDown by rememberSavedInProcess { false }
    // Направление считается при смене значения и держится, пока идёт прокрутка; запоминается после кадра.
    val down = if (value != lastValue) value < lastValue else lastDown
    SideEffect {
        lastDown = down
        lastValue = value
    }
    val columns = RollingText.columns(shownText, text)
    // Состояния разрядов по ключу; новые изменения подхватываются на лету, прокрутка идёт к последнему значению.
    val states = remember { mutableStateMapOf<Int, MutableTransitionState<Char?>>() }
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        columns.forEach { column ->
            key(column.key) {
                val state = remember { MutableTransitionState(column.old) }
                state.targetState = column.new
                DisposableEffect(state) {
                    states[column.key] = state
                    onDispose { states.remove(column.key) }
                }
                rememberTransition(state, label = "digit").AnimatedContent(
                    transitionSpec = {
                        val still = reduced || !initialState.isDigitChar() && !targetState.isDigitChar()
                        if (still) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val sign = if (down) -1 else 1
                            (slideInVertically(tween(Motion.DIGITS)) { sign * it } + fadeIn(tween(Motion.DIGITS))) togetherWith
                                (slideOutVertically(tween(Motion.DIGITS)) { -sign * it } + fadeOut(tween(Motion.DIGITS)))
                        }.using(
                            // Ширина разряда меняется вместе с прокруткой, без неё — сразу (иначе по умолчанию пружина).
                            SizeTransform(clip = true) { _, _ -> if (still) snap() else tween(Motion.DIGITS) },
                        )
                    },
                ) { shown ->
                    if (shown != null) Text(shown.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }
    LaunchedEffect(text) {
        snapshotFlow { states.values.all { it.isIdle } }.first { it }
        shownText = text
    }
}

/**
 * [RollingAmount], который при нехватке места уменьшает шрифт, чтобы сумма уместилась в одну строку без обрезки
 * (spec home-screen «Сумма «можно сегодня» при крупном шрифте»). При обычном шрифте размер не меняется; меньше
 * [minFontSize] не уменьшается.
 */
@Composable
fun FittedRollingAmount(
    text: String,
    value: Long,
    style: TextStyle,
    color: Color,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        val fitted = remember(text, style, maxWidth, measurer) {
            val width = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            if (maxWidth == Constraints.Infinity || width <= maxWidth) {
                style
            } else {
                // Небольшой запас: цифры рисуются по одной, без общего кернинга строки.
                val scale = maxWidth.toFloat() / width * FIT_MARGIN
                val size = (style.fontSize.value * scale).coerceAtLeast(minFontSize.value).sp
                style.copy(fontSize = size, lineHeight = if (style.lineHeight.isSp) (style.lineHeight.value * scale).sp else style.lineHeight)
            }
        }
        RollingAmount(text, value, fitted, color)
    }
}

private const val FIT_MARGIN = 0.96f
