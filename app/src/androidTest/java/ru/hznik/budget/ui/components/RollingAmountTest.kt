package ru.hznik.budget.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.theme.Motion

/** Прокрутка цифр (spec ui-motion «Прокрутка цифр суммы», «Доступность анимаций»; задача 2.2). */
@RunWith(AndroidJUnit4::class)
class RollingAmountTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(start: Long, reduced: Boolean): (Long) -> Unit {
        var value by mutableLongStateOf(start)
        rule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                RollingAmount(MoneyFormat.amount(value), value, TextStyle(fontSize = 20.sp), Color.Black, Modifier.testTag(TAG))
            }
        }
        return { value = it }
    }

    private fun width() = rule.onNodeWithTag(TAG).getBoundsInRoot().let { it.right - it.left }

    @Test
    fun rollingShowsOnlyTheFinalTextAndShrinksSmoothly() {
        val set = show(1_000_00, reduced = false)
        val before = width()
        rule.mainClock.autoAdvance = false
        set(950_00)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(Motion.DIGITS / 2L)

        // Посередине прокрутки TalkBack и тесты видят только итоговую сумму, а лишний разряд ещё уходит.
        rule.onNodeWithTag(TAG).assertTextEquals(MoneyFormat.amount(950_00))
        val middle = width()

        rule.mainClock.advanceTimeBy(Motion.DIGITS.toLong() * 2)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG).assertTextEquals(MoneyFormat.amount(950_00))
        val after = width()
        assertTrue("ширина во время прокрутки $middle между $before и $after", middle < before && middle > after)
    }

    @Test
    fun reducedMotionShowsTheNewSumAtOnce() {
        val set = show(1_000_00, reduced = true)
        rule.mainClock.autoAdvance = false
        set(950_00)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val immediate = width()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG).assertTextEquals(MoneyFormat.amount(950_00))
        assertEquals(width(), immediate)
    }

    private companion object {
        const val TAG = "sum"
    }
}
