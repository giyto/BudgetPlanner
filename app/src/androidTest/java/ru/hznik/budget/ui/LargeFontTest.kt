package ru.hznik.budget.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.ui.components.FittedRollingAmount
import ru.hznik.budget.ui.components.RollingAmount
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.home.HOME_HERO_TAG
import ru.hznik.budget.ui.home.HomeScreen
import ru.hznik.budget.ui.home.HomeUiState
import ru.hznik.budget.ui.operations.EditorUiState
import ru.hznik.budget.domain.usecase.InputType
import ru.hznik.budget.ui.operations.OperationEditorScreen
import ru.hznik.budget.ui.theme.BudgetPlannerTheme
import java.time.LocalDate

/** Самый крупный шрифт Android (200 %): главная (spec home-screen «Сумма «можно сегодня» при крупном шрифте»; задача 6.2) и «Новая операция» (задача 6.3). */
@RunWith(AndroidJUnit4::class)
class LargeFontTest {
    @get:Rule
    val rule = createComposeRule()

    private val amount = MoneyFormat.amount(12_345_00)

    /** Самая длинная сумма, которая встречается на главной: перерасход в миллион рублей. */
    private val longAmount = MoneyFormat.MINUS + MoneyFormat.amount(1_234_567_00)

    @Test
    fun amountShrinksToFitAtLargestFont() {
        rule.setContent {
            BudgetPlannerTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    Column {
                        Box(Modifier.width(CARD_WIDTH.dp)) {
                            FittedRollingAmount(longAmount, -1_234_567_00, MaterialTheme.typography.displayLarge, Color.Black, 22.sp, Modifier.testTag(FITTED))
                        }
                        // Без подбора та же сумма не влезает: строка обрезает последние символы.
                        Box(Modifier.width(CARD_WIDTH.dp)) {
                            RollingAmount(longAmount, -1_234_567_00, MaterialTheme.typography.displayLarge, Color.Black, Modifier.testTag(PLAIN))
                        }
                    }
                }
            }
        }
        val fitted = rule.onNodeWithTag(FITTED).getBoundsInRoot()
        val plain = rule.onNodeWithTag(PLAIN).getBoundsInRoot()
        assertTrue("шрифт не уменьшился: ${fitted.bottom - fitted.top} против ${plain.bottom - plain.top}", fitted.bottom - fitted.top < plain.bottom - plain.top)
        val width = (fitted.right - fitted.left).value
        assertTrue("ширина суммы $width больше карточки $CARD_WIDTH", width <= CARD_WIDTH + 0.5f)
    }

    @Test
    fun amountKeepsMockupSizeAtNormalFont() {
        rule.setContent {
            BudgetPlannerTheme {
                Column {
                    Box(Modifier.width(CARD_WIDTH.dp)) {
                        FittedRollingAmount(amount, 12_345_00, MaterialTheme.typography.displayLarge, Color.Black, 22.sp, Modifier.testTag(FITTED))
                    }
                    RollingAmount("1", 1, MaterialTheme.typography.displayLarge, Color.Black, Modifier.testTag(PLAIN))
                }
            }
        }
        // Высота строки та же, что у суммы без подбора: размер шрифта не уменьшился.
        val fitted = rule.onNodeWithTag(FITTED).getBoundsInRoot()
        val plain = rule.onNodeWithTag(PLAIN).getBoundsInRoot()
        assertEquals((plain.bottom - plain.top).value, (fitted.bottom - fitted.top).value, 0.5f)
    }

    @Test
    fun homeActionsStayReachableAtLargestFont() {
        val today = LocalDate.of(2026, 10, 13)
        val status = BudgetCalculator.calculate(Period(startDate = today, nextIncomeDate = today.plusDays(1), startAmountKopecks = 12_345_00), emptyList(), emptyList(), today)
        rule.setContent {
            BudgetPlannerTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    HomeScreen(HomeUiState(loaded = true, status = status), {}, {}, {}, {}, {}, {})
                }
            }
        }
        rule.onNodeWithTag(HOME_HERO_TAG).assertIsDisplayed()
        rule.onNode(hasContentDescription("Добавить операцию") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Период до", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun newOperationStaysUsableAtLargestFont() {
        val today = LocalDate.of(2026, 10, 13)
        val categories = listOf("Продукты", "Кафе и рестораны", "Транспорт", "Здоровье", "Развлечения", "Другое").mapIndexed { i, name ->
            Category(id = i + 1L, kind = CategoryKind.EXPENSE, name = name, icon = "cart", color = "blue", sortOrder = i)
        }
        val state = EditorUiState(
            isEdit = false, loaded = true, inputType = InputType.EXPENSE, amountInput = "1250",
            topCategories = categories, allCategories = categories, selectedCategoryId = 1, date = today, today = today, comment = "",
        )
        rule.setContent {
            BudgetPlannerTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    OperationEditorScreen(state, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        rule.onNode(hasText("Возврат") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasContentDescription("Сумма", substring = true)).assertIsDisplayed()
        rule.onNode(hasContentDescription("Стереть") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasContentDescription("5") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Продукты") and hasClickAction()).assertIsDisplayed()
        assertTrue("название категории разорвано по буквам", wordsWhole("Кафе и рестораны"))
        rule.onNode(hasText("Сохранить") and hasClickAction()).assertIsDisplayed()
    }

    /** Подпись [text] на экране переносится только между словами (по раскладке текста из семантики). */
    private fun wordsWhole(text: String): Boolean {
        val node = rule.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        return (0 until layout.lineCount - 1).all { line ->
            val end = layout.getLineEnd(line)
            text.getOrNull(end - 1)?.isWhitespace() == true || text.getOrNull(end)?.isWhitespace() == true
        } && !layout.isLineEllipsized(layout.lineCount - 1)
    }

    private companion object {
        /** Ширина содержимого главной карточки на телефоне шириной 360 dp: 360 − 2·16 − 2·22. */
        const val CARD_WIDTH = 284
        const val FITTED = "fitted"
        const val PLAIN = "plain"
    }
}
