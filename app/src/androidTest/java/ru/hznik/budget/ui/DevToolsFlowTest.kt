package ru.hznik.budget.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/**
 * Раздел «Для разработки» в отладочной сборке после переноса в `src/debug` (задача 9.2): демо-данные и экран образцов
 * на месте. UI-тесты идут на отладочной сборке; что в release его нет, проверяется по собранному файлу.
 */
@HiltAndroidTest
class DevToolsFlowTest : FlowTestBase() {
    @Test
    fun debugBuildShowsDemoDataAndSamples() {
        waitForHero("Можно потратить сегодня")
        openSettings()
        waitForText("Настройки")
        rule.onNode(hasText("Заполнить демо-данными") and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Образцы уведомлений") and hasClickAction()).performScrollTo().performClick()
        waitForText("Копировать все")
    }
}
