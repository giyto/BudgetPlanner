package ru.hznik.budget.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.theme.Motion
import ru.hznik.budget.ui.theme.motionTween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.animateContentSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.AnimationSpec
import java.util.UUID
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.runtime.saveable.Saver
import androidx.compose.animation.slideInVertically

/**
 * Отклик на нажатие (spec ui-motion «Отклик на нажатие»): элемент чуть уменьшается под пальцем и возвращается при
 * отпускании. Нажатия не перехватываются — клик и прокрутка работают как раньше. При выключенных в системе анимациях
 * отклика нет.
 */
fun Modifier.pressScale(): Modifier = composed {
    if (LocalReducedMotion.current) return@composed this
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, tween(Motion.PRESS), label = "press")
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                // Отпускание, отмена или начало прокрутки — карточка возвращается.
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

private const val PRESSED_SCALE = 0.97f

/**
 * Строка ленивого списка плавно появляется, исчезает и переставляется (spec ui-motion «Анимация списков»); у списка
 * должны быть ключи. [fadeIn] `false` — появление строка делает сама (`AppearFromBelow`). При выключенных анимациях — сразу.
 */
@Composable
fun LazyItemScope.itemMotion(fadeIn: Boolean = true): Modifier =
    if (LocalReducedMotion.current) {
        Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    } else {
        Modifier.animateItem(
            fadeInSpec = if (fadeIn) tween(Motion.ITEM) else null,
            placementSpec = tween(Motion.ITEM),
            fadeOutSpec = tween(Motion.ITEM),
        )
    }

/**
 * Содержимое въезжает снизу с проявлением при первом показе (spec ui-motion «Анимация чата помощника»); [animate]
 * `false` — показывается сразу (история при открытии, выключенные анимации).
 */
@Composable
fun AppearFromBelow(animate: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val state = remember { MutableTransitionState(!animate || reduced).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = slideInVertically(tween(Motion.ITEM)) { it / 2 } + fadeIn(tween(Motion.ITEM)),
        exit = ExitTransition.None,
    ) { content() }
}

/** Плавная смена высоты, когда внутри появляется или пропадает строка; при выключенных анимациях — сразу. */
fun Modifier.contentSizeMotion(): Modifier = composed {
    if (LocalReducedMotion.current) this else animateContentSize(tween(Motion.ITEM))
}

/**
 * Ряд вариантов, у которого выделение плавно переезжает на выбранный (spec ui-motion «Переключатели вариантов»).
 * [content] получает модификатор для каждого варианта по его номеру: его ставят на видимую область варианта (внутри
 * Surface, а не на сам Surface — у того зона нажатия больше), по ней считается место выделения. Сами варианты
 * прозрачные, выделение рисуется под ними цветом [indicatorColor]. До первого измерения и при выключенных анимациях
 * выделение встаёт сразу.
 */
@Composable
fun SlidingSelectionRow(
    selectedIndex: Int,
    indicatorColor: Color,
    indicatorShape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(segment: (Int) -> Modifier) -> Unit,
) {
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val target = bounds[selectedIndex]
    var placed by remember { mutableStateOf(false) }
    val jump = !placed || LocalReducedMotion.current
    val spec: AnimationSpec<Float> = if (jump) snap() else tween(Motion.ITEM)
    val animatedLeft by animateFloatAsState(target?.left ?: 0f, spec, label = "segment left")
    val animatedWidth by animateFloatAsState(target?.width ?: 0f, spec, label = "segment width")
    // Сразу на месте, без кадра пустоты, пока анимированное значение догоняет первое измерение.
    val left = if (jump) target?.left ?: 0f else animatedLeft
    val width = if (jump) target?.width ?: 0f else animatedWidth
    if (target != null) SideEffect { placed = true }
    Box(modifier) {
        if (target != null) {
            Box(
                Modifier
                    .layout { measurable, _ ->
                        val w = width.roundToInt()
                        val h = target.height.roundToInt()
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { placeable.place(left.roundToInt(), target.top.roundToInt()) }
                    }
                    .background(indicatorColor, indicatorShape),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.onPlaced { rowCoordinates[0] = it }) {
            content { index ->
                Modifier.onGloballyPositioned { segment ->
                    val row = rowCoordinates[0]
                    if (row != null && row.isAttached && segment.isAttached) bounds[index] = row.localBoundingBoxOf(segment)
                }
            }
        }
    }
}

/** Метка процесса: значения, сохранённые до перезапуска процесса, не восстанавливаются. */
private val PROCESS_TOKEN = UUID.randomUUID().toString()

/**
 * Состояние, которое переживает уход на другой экран (сохраняется вместе с экраном), но не перезапуск процесса: на
 * следующий день приложение открывается с нужными значениями сразу, без анимации от вчерашних (spec ui-motion
 * «Открытие главной»). [T] должен сохраняться в Bundle (число, строка).
 */
@Composable
fun <T> rememberSavedInProcess(init: () -> T): MutableState<T> =
    rememberSaveable(
        saver = Saver<MutableState<T>, Any>(
            save = { arrayListOf(PROCESS_TOKEN, it.value) },
            restore = { saved ->
                val list = saved as List<*>
                @Suppress("UNCHECKED_CAST")
                if (list[0] == PROCESS_TOKEN) mutableStateOf(list[1] as T) else null
            },
        ),
    ) { mutableStateOf(init()) }

/** Цвет, который плавно меняется к [target] (spec ui-motion «Плавная смена цвета»); при выключенных анимациях — сразу. */
@Composable
fun animatedColor(target: Color): Color = animateColorAsState(target, motionTween(Motion.ITEM), label = "color").value

/**
 * Значение, которое плавно идёт к [target] (spec ui-motion «Плавные полоски»). При первом показе сразу равно цели.
 * Последнее значение сохраняется вместе с экраном: если цель изменилась, пока был открыт другой экран, после
 * возврата значение доезжает от прежнего.
 */
@Composable
fun animateFloatSaved(target: Float, durationMillis: Int = Motion.BAR): State<Float> {
    var from by rememberSavedInProcess { target }
    val animatable = remember { Animatable(from) }
    val spec = motionTween<Float>(durationMillis)
    LaunchedEffect(target) {
        from = target
        animatable.animateTo(target, spec)
    }
    return animatable.asState()
}

/**
 * Плашка, которая раскрывается по высоте с проявлением и сворачивается обратно (spec ui-motion «Появление плашек»).
 * Плашка показана, пока [value] не `null`; при сворачивании показывается последнее значение. Отступы до соседей
 * ([top], [bottom]) входят в анимацию, чтобы содержимое ниже сдвигалось плавно, без скачка. При первом показе экрана
 * плашка сразу на месте.
 *
 * [saveable] — значение можно сохранить вместе с экраном (число и т. п.): тогда плашка сворачивается и после возврата
 * с другого экрана, на котором её причина исчезла.
 */
@Composable
fun <T : Any> AnimatedBanner(
    value: T?,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    saveable: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val last: MutableState<T?> = if (saveable) rememberSavedInProcess { value } else remember { mutableStateOf(value) }
    if (value != null) last.value = value
    val state = remember { MutableTransitionState(last.value != null) }
    state.targetState = value != null
    // Свернулась — забываем значение, иначе после возврата на экран плашка снова сворачивалась бы.
    LaunchedEffect(state.isIdle, state.currentState) {
        if (state.isIdle && !state.currentState) last.value = null
    }
    AnimatedVisibility(
        visibleState = state,
        enter = if (reduced) EnterTransition.None else expandVertically(tween(Motion.ITEM)) + fadeIn(tween(Motion.ITEM)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(Motion.ITEM)) + fadeOut(tween(Motion.ITEM)),
        modifier = modifier,
    ) {
        val shown = last.value ?: return@AnimatedVisibility
        Box(Modifier.padding(top = top, bottom = bottom).pressScale()) { content(shown) }
    }
}
