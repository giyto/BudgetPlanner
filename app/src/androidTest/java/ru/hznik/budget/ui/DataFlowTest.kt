package ru.hznik.budget.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TestBackups
import ru.hznik.budget.domain.export.BackupCheck
import ru.hznik.budget.ui.userdata.BACKUP_PASSWORD_TAG
import ru.hznik.budget.ui.userdata.BACKUP_REPEAT_TAG
import ru.hznik.budget.ui.userdata.EXPORT_COUNT_TAG
import ru.hznik.budget.ui.userdata.EXPORT_TO_TAG
import ru.hznik.budget.ui.userdata.RESTORE_FILE_TAG
import ru.hznik.budget.ui.userdata.RESTORE_PASSWORD_TAG
import java.io.File
import java.time.Instant

/**
 * Раздел «Данные» в настройках (spec data-export; задачи 2.3, 3.4, 4.2, 5.1, 5.2). Системные окна выбора файла
 * подменяются ответом Espresso Intents; копия и удаление — [TestBackups].
 */
@HiltAndroidTest
class DataFlowTest : FlowTestBase() {
    private var intents = false

    @After
    fun releaseIntents() {
        if (intents) Intents.release()
    }

    /** Ответ системного окна выбора файла: выбран [file]. */
    private fun pickFile(action: String, file: File) {
        if (!intents) Intents.init().also { intents = true }
        intending(hasAction(action)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
    }

    private fun openData(item: String) {
        waitForHero("Можно потратить сегодня")
        openSettings()
        // Пункт ниже края экрана есть в дереве, но нажатие по нему мимо: сначала прокрутка.
        rule.waitUntil(5_000) { scrollTo(item) }
        tapText(item)
    }

    /** Настройки — обычный прокручиваемый столбец: прокручивается к самому пункту. */
    private fun scrollTo(text: String): Boolean = runCatching { rule.onNode(hasText(text)).performScrollTo() }.isSuccess

    private fun countSays(text: String) =
        rule.onAllNodes(hasTestTag(EXPORT_COUNT_TAG) and hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun dataSectionHasFourActions() {
        waitForHero("Можно потратить сегодня")
        openSettings()
        listOf("Экспорт в CSV", "Резервная копия", "Восстановить из копии", "Удалить все данные").forEach { item ->
            rule.waitUntil(5_000) { scrollTo(item) }
            rule.onNode(hasText(item) and hasClickAction()).assertIsEnabled()
        }
        rule.assertTouchTargets()
    }

    @Test
    fun exportCountsOperationsAndSaysWhenThereAreNone() {
        repeat(3) { insert("Продукты", 100L + it, date = TEST_TODAY) }
        openData("Экспорт в CSV")
        rule.waitUntil(5_000) { countSays("3 операции") }
        rule.onNode(hasText("Сохранить файл") and hasClickAction()).assertIsEnabled()

        // «По» 5 октября: операции 13-го не попадают, файл создать нельзя.
        rule.onNodeWithTag(EXPORT_TO_TAG).performClick()
        rule.onNode(calendarDay(5)).performClick()
        tapText("Готово")
        rule.waitUntil(5_000) { countSays("Нет операций за эти даты") }
        rule.onNode(hasText("Сохранить файл") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun exportWritesCsvToChosenFile() {
        insert("Продукты", 450, date = TEST_TODAY, merchant = "Пятёрочка")
        insert("Кафе и рестораны", 340, date = TEST_TODAY, hour = 15, merchant = "Кофемания")
        val file = File(rule.activity.cacheDir, "export_test.csv").apply { delete() }
        pickFile(Intent.ACTION_CREATE_DOCUMENT, file)
        openData("Экспорт в CSV")
        rule.waitUntil(5_000) { countSays("2 операции") }
        tapText("Сохранить файл")
        waitForText("Файл сохранён")
        val lines = file.readText().removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals("Дата;Время;Тип;Сумма;Категория;Магазин или описание;Комментарий;Источник;Банк;Черновик", lines.first())
        assertEquals(listOf("Пятёрочка", "Кофемания"), lines.drop(1).map { it.split(";")[5] })
        assertTrue(lines[1].contains(";-450,00;"))
    }

    @Test
    fun backupChecksPasswordsAndSavesOnce() {
        val file = File(rule.activity.cacheDir, "backup_test.bpbackup")
        pickFile(Intent.ACTION_CREATE_DOCUMENT, file)
        openData("Резервная копия")
        val save = hasText("Сохранить копию") and hasClickAction()
        rule.waitUntil(5_000) { rule.onAllNodes(save).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(save).assertIsNotEnabled()

        rule.onNodeWithTag(BACKUP_PASSWORD_TAG).performTextInput("осень")
        waitForText("Не короче 8 символов")
        rule.onNodeWithTag(BACKUP_PASSWORD_TAG).performTextReplacement("осень2026!")
        rule.onNodeWithTag(BACKUP_REPEAT_TAG).performTextInput("осень2025!")
        waitForText("Пароли не совпадают")
        rule.onNode(save).assertIsNotEnabled()
        rule.onNodeWithTag(BACKUP_REPEAT_TAG).performTextReplacement("осень2026!")
        waitForText("Пароли совпадают")
        rule.onNode(save).assertIsEnabled()

        // Пока копия создаётся, кнопка занята: второе нажатие ничего не делает.
        val gate = CompletableDeferred<Unit>()
        TestBackups.createGate = gate
        rule.onNode(save).performClick()
        waitForText("Сохраняю…")
        rule.onNode(hasText("Сохраняю…") and hasClickAction()).performClick()
        gate.complete(Unit)
        waitForText("Копия сохранена")
        assertEquals(listOf(Uri.fromFile(file).toString() to "осень2026!"), TestBackups.created.toList())
    }

    @Test
    fun restoreWithWrongPasswordChangesNothing() {
        TestBackups.nextCheck = BackupCheck.WrongPassword
        val file = File(rule.activity.cacheDir, "budget-backup-2026-10-17.bpbackup").apply { writeText("копия") }
        pickFile(Intent.ACTION_OPEN_DOCUMENT, file)
        openData("Восстановить из копии")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(RESTORE_FILE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(RESTORE_FILE_TAG).performClick()
        waitForText("budget-backup-2026-10-17.bpbackup")
        rule.onNodeWithTag(RESTORE_PASSWORD_TAG).performTextInput("неверный")
        tapText("Восстановить")
        waitForText("Неверный пароль или файл повреждён")
        assertEquals(0, TestBackups.scheduled)
        assertEquals(0, TestBackups.restarts)
    }

    @Test
    fun restoreAsksToReplaceDataAndRestarts() {
        TestBackups.nextCheck = BackupCheck.Valid(Instant.parse("2026-10-17T09:00:00Z"), backupOperations = 1_284, currentOperations = 312)
        val file = File(rule.activity.cacheDir, "copy.bpbackup").apply { writeText("копия") }
        pickFile(Intent.ACTION_OPEN_DOCUMENT, file)
        openData("Восстановить из копии")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(RESTORE_FILE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(RESTORE_FILE_TAG).performClick()
        waitForText("copy.bpbackup")
        rule.onNodeWithTag(RESTORE_PASSWORD_TAG).performTextInput("осень2026!")
        tapText("Восстановить")

        waitForText("Заменить данные на телефоне?")
        waitForText("Сейчас здесь 312 операций", substring = true)
        waitForText("1284 операции и настройки из копии от 17 октября", substring = true)
        tapText("Отмена")
        rule.waitUntil(5_000) { TestBackups.discarded == 1 }
        assertEquals(0, TestBackups.restarts)

        // Пароль после проверки стирается: ввести снова.
        rule.onNodeWithTag(RESTORE_PASSWORD_TAG).performTextInput("осень2026!")
        tapText("Восстановить")
        tapText("Заменить и перезапустить")
        rule.waitUntil(5_000) { TestBackups.restarts == 1 }
        assertEquals(1, TestBackups.scheduled)
    }

    @Test
    fun deleteAllDataAsksFirstAndOffersBackup() {
        openData("Удалить все данные")
        waitForText("Удалить все данные?")
        tapText("Отмена")
        assertEquals(0, TestBackups.wipes)

        scrollTo("Удалить все данные")
        tapText("Удалить все данные")
        tapText("Сначала сделать копию")
        waitForText("Без пароля файл не открыть", substring = true)
        assertEquals(0, TestBackups.wipes)

        rule.activity.onBackPressedDispatcher.let { dispatcher -> rule.runOnUiThread { dispatcher.onBackPressed() } }
        rule.waitUntil(5_000) { scrollTo("Удалить все данные") }
        tapText("Удалить все данные")
        tapText("Удалить всё")
        // Перед отметкой удаляется файл образцов через MediaStore: на загруженном эмуляторе это дольше секунды.
        rule.waitUntil(15_000) { TestBackups.restarts == 1 }
        assertEquals(1, TestBackups.wipes)
    }
}
