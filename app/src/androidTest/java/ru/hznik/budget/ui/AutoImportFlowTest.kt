package ru.hznik.budget.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.hznik.budget.domain.notifications.AutoImportSettings
import ru.hznik.budget.ui.autoimport.AUTO_IMPORT_STOPPED_TAG
import javax.inject.Inject

/** Включение автоимпорта и плашка «Автоимпорт остановлен» (spec notification-import). В тестах доступа к уведомлениям нет. */
@HiltAndroidTest
class AutoImportFlowTest : FlowTestBase() {
    @Inject lateinit var settings: AutoImportSettings

    @Test
    fun noStoppedBannerForUserWhoNeverEnabledIt() {
        waitForHero("Можно потратить сегодня")
        rule.onNodeWithTag(AUTO_IMPORT_STOPPED_TAG).assertDoesNotExist()
    }

    @Test
    fun stoppedBannerWhenAccessWasRevoked() {
        waitForHero("Можно потратить сегодня")
        runBlocking { settings.setWasEnabled() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(AUTO_IMPORT_STOPPED_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Автоимпорт остановлен")).assertIsDisplayed()
    }

    @Test
    fun settingsShowNoAccessAndNextStep() {
        rule.onNode(hasContentDescription("Настройки") and hasClickAction()).performClick()
        waitForText("Нет доступа к уведомлениям")
        rule.onNode(hasText("Разрешить доступ к уведомлениям") and hasClickAction()).assertIsDisplayed()
    }
}
