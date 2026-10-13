package ru.hznik.budget.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Доменный слой остаётся чистым Kotlin: без импортов Android и AndroidX (раздел 8.1 ТЗ). */
class DomainPurityTest {
    // Gradle запускает unit-тесты из каталога модуля app.
    private val domainDir = File("src/main/java/ru/hznik/budget/domain")
    private val forbiddenImport = Regex("""^\s*import\s+(android|androidx)\.""")

    @Test
    fun `domain sources do not import android or androidx`() {
        assertTrue("Не найден каталог ${domainDir.absolutePath}", domainDir.isDirectory)

        val violations = domainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> forbiddenImport.containsMatchIn(line) }
                    .map { (index, line) -> "${file.relativeTo(domainDir)}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertTrue("Доменный слой импортирует Android:\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
