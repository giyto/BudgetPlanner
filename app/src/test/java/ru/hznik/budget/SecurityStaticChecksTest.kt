package ru.hznik.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Статические проверки безопасности по таблице раздела 10.2 и разделу 11 ТЗ: манифест, правила бэкапа,
 * сетевая конфигурация и логи. Читают файлы проекта, как [ru.hznik.budget.domain.DomainPurityTest].
 */
class SecurityStaticChecksTest {
    // Gradle запускает unit-тесты из каталога модуля app.
    private val mainDir = File("src/main")
    private val sourcesDir = File(mainDir, "java/ru/hznik/budget")
    private val xmlDir = File(mainDir, "res/xml")

    // --- Манифест (раздел 11 ТЗ, spec app-foundation «Разрешения приложения», раздел 10.3 «не читает SMS») ---

    @Test
    fun `manifest requests only biometric, internet and post notifications permissions`() {
        val manifest = parse(File(mainDir, "AndroidManifest.xml"))

        // Записи `tools:node="remove"` убирают разрешение, пришедшее из библиотеки, а не запрашивают его.
        val requested = (elements(manifest, "uses-permission") + elements(manifest, "uses-permission-sdk-23"))
            .filter { it.getAttributeNS(TOOLS_NS, "node") != "remove" }
        assertEquals(
            "Разрешения манифеста отличаются от раздела 11 ТЗ",
            setOf(
                "android.permission.USE_BIOMETRIC",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requested.map { it.android("name") }.toSet(),
        )
        assertEquals("Разрешение объявлено дважды", 3, requested.size)
    }

    @Test
    fun `manifest disables backup and points to backup, extraction and network rules`() {
        val application = elements(parse(File(mainDir, "AndroidManifest.xml")), "application").single()

        assertEquals("false", application.android("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.android("dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.android("fullBackupContent"))
        assertEquals("@xml/network_security_config", application.android("networkSecurityConfig"))
        assertNotEquals("Открытый HTTP разрешён в манифесте", "true", application.android("usesCleartextTraffic"))
    }

    @Test
    fun `notification listener is bound only by the system and app actions are not exported`() {
        val manifest = parse(File(mainDir, "AndroidManifest.xml"))

        // Доступ к уведомлениям выдаёт только система (раздел 11 ТЗ: BIND_NOTIFICATION_LISTENER_SERVICE у сервиса).
        val listener = elements(manifest, "service").single { it.android("name") == ".notifications.BankNotificationListener" }
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.android("permission"))

        // Переходы из виджета и уведомлений может прислать только само приложение.
        val alias = elements(manifest, "activity-alias").single { it.android("name") == ".AppActionAlias" }
        assertEquals("false", alias.android("exported"))
    }

    // --- Бэкап (раздел 10.2 ТЗ, spec data-encryption «Исключение из бэкапа») ---

    @Test
    fun `data extraction rules exclude every domain from cloud backup and device transfer`() {
        val rules = parse(File(xmlDir, "data_extraction_rules.xml"))

        listOf("cloud-backup", "device-transfer").forEach { section ->
            val element = elements(rules, section).singleOrNull()
            assertTrue("В data_extraction_rules нет раздела <$section>", element != null)
            assertExcludesAllDomains("data_extraction_rules <$section>", element!!)
        }
    }

    @Test
    fun `full backup rules exclude every domain`() {
        val rules = parse(File(xmlDir, "backup_rules.xml"))

        assertEquals("full-backup-content", rules.documentElement.tagName)
        assertExcludesAllDomains("backup_rules", rules.documentElement)
    }

    // --- Сеть (раздел 10.2 ТЗ «Перехват трафика к модели», spec ai-assistant «Защищённое соединение») ---

    @Test
    fun `network config forbids cleartext and trusts the russian root only for gigachat hosts`() {
        val config = parse(File(xmlDir, "network_security_config.xml"))

        val base = elements(config, "base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertTrue("Базовая конфигурация задаёт свои сертификаты", elements(base, "trust-anchors").isEmpty())

        val domainConfig = elements(config, "domain-config").single()
        assertNotEquals("Открытый HTTP разрешён для доменов GigaChat", "true", domainConfig.getAttribute("cleartextTrafficPermitted"))
        val domains = elements(domainConfig, "domain")
        assertEquals(GIGACHAT_HOSTS, domains.map { it.textContent.trim() }.toSet())
        domains.forEach { domain ->
            assertNotEquals("Сертификат доверяется поддоменам ${domain.textContent.trim()}", "true", domain.getAttribute("includeSubdomains"))
        }
        assertEquals(
            setOf("system", "@raw/russian_trusted_root_ca"),
            elements(domainConfig, "certificates").map { it.getAttribute("src") }.toSet(),
        )

        // Свой сертификат и пользовательские сертификаты нигде больше не доверяются.
        val certificatesOutsideGigaChat = elements(config, "certificates")
            .filter { it.getAttribute("src") != "system" && !it.isInside(domainConfig) }
        assertTrue("Сертификаты вне доменов GigaChat: ${certificatesOutsideGigaChat.map { it.getAttribute("src") }}", certificatesOutsideGigaChat.isEmpty())
    }

    @Test
    fun `production code connects only to gigachat over https`() {
        // Адрес в любом месте строкового литерала, в любом регистре.
        val url = Regex(""""[^"\n]*?(?i:(https?))://([^/:"\s]+)""")
        val violations = sources().flatMap { (name, text) ->
            url.findAll(text)
                .filter { it.groupValues[1].lowercase() != "https" || it.groupValues[2].lowercase() !in GIGACHAT_HOSTS }
                .map { "$name:${lineOf(text, it.range.first)}: ${text.lineAt(it.range.first)}" }
        }

        assertTrue("Адреса вне GigaChat или без HTTPS:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    // --- Логи (раздел 10.2 ТЗ «Логи с персональными данными»; specs ai-assistant, notification-import, balance-reconciliation) ---

    /**
     * В логах допустимы только предупреждения и ошибки с постоянным текстом и, самое большее, именем класса исключения:
     * `Log.w(TAG, "Текст: ${e.javaClass.simpleName}")`. Само исключение не передаётся: его сообщение и стек могут
     * содержать суммы, магазины или текст уведомления. Единственное исключение — `runLogged(tag, what)` из
     * `common/Failures.kt`, который подставляет [what]; что туда передаются только постоянные строки, проверяет
     * следующий тест.
     */
    @Test
    fun `logs carry only constant text and the exception class name`() {
        val anyLogCall = Regex("""\bLog\.\w+\s*\(""")
        val constantText = """[^"$\n]*(\$\{\w+\.javaClass\.simpleName\})?[^"$\n]*"""
        val allowedLogCall = Regex("""\bLog\.[we]\(\s*[A-Z_]+\s*,\s*"$constantText"\s*\)""")
        val runLoggedLogCall = Regex("""\bLog\.[we]\(\s*tag\s*,\s*"\${'$'}what: \${'$'}\{e\.javaClass\.simpleName\}"\s*\)""")

        val violations = sources().flatMap { (name, text) ->
            val allowedStarts = allowedLogCall.findAll(text).map { it.range.first }.toSet() +
                if (RUN_LOGGED_DEFINITION.containsMatchIn(text)) runLoggedLogCall.findAll(text).map { it.range.first }.toSet() else emptySet()
            anyLogCall.findAll(text)
                .filter { it.range.first !in allowedStarts }
                .map { "$name:${lineOf(text, it.range.first)}: ${text.lineAt(it.range.first)}" }
        }

        assertTrue("Вызовы Log, которые могут вывести данные:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `runLogged gets only a constant tag and a constant description`() {
        // Объявление самой функции («fun <T> runLogged(tag: String, …») вызовом не считается.
        val anyCall = Regex("""(?<![\w.])(?<!fun <T> )runLogged\s*\(""")
        val allowedCall = Regex("""(?<![\w.])runLogged\(\s*[A-Z_]+\s*,\s*"[^"$\n]*"\s*\)""")

        val violations = sources().flatMap { (name, text) ->
            val allowedStarts = allowedCall.findAll(text).map { it.range.first }.toSet()
            anyCall.findAll(text)
                .filter { it.range.first !in allowedStarts }
                .map { "$name:${lineOf(text, it.range.first)}: ${text.lineAt(it.range.first)}" }
        }

        assertTrue("runLogged с текстом, который может содержать данные:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `production code has no debug logging or console output`() {
        val forbidden = listOf(
            Regex("""\bLog\.[dvi]\s*\("""),
            Regex("""\bLog\.wtf\s*\("""),
            Regex("""\bLog\.println\s*\("""),
            Regex("""(?<![\w.])println\s*\("""),
            Regex("""(?<![\w.])print\s*\("""),
            Regex("""\.printStackTrace\s*\("""),
            Regex("""\bSystem\.(out|err)\b"""),
            Regex("""^\s*import\s+android\.util\.Log\.""", RegexOption.MULTILINE),
            Regex("""^\s*import\s+android\.util\.Log\s+as\s""", RegexOption.MULTILINE),
            Regex("""\bTimber\b"""),
            // Логирование HTTP выводит тексты вопросов, ответов и токен (spec ai-assistant «Защищённое соединение»).
            Regex("""\bHttpLoggingInterceptor\b"""),
            Regex("""\bio\.ktor\.client\.plugins\.logging\b"""),
        )

        val violations = sources().flatMap { (name, text) ->
            forbidden.flatMap { pattern ->
                pattern.findAll(text).map { "$name:${lineOf(text, it.range.first)}: ${text.lineAt(it.range.first)}" }
            }
        }

        assertTrue("Отладочный вывод в коде приложения:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    // --- Аналитика и реклама (раздел 10.3 ТЗ «не содержит аналитики и рекламы») ---

    @Test
    fun `dependencies contain no analytics, ads or crash reporting sdks`() {
        val files = listOf(File("../gradle/libs.versions.toml"), File("build.gradle.kts"))
        val violations = files.flatMap { file ->
            assertTrue("Не найден файл ${file.absolutePath}", file.isFile)
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("#") && !line.trimStart().startsWith("//") }
                .filter { (_, line) -> TRACKING_SDKS.containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("Зависимости аналитики или рекламы:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    private fun assertExcludesAllDomains(where: String, section: Element) {
        assertTrue("$where: есть <include>, бэкап не должен ничего включать", elements(section, "include").isEmpty())
        val excluded = elements(section, "exclude")
            .filter { it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }
            .toSet()
        assertEquals("$where: исключены не все домены", BACKUP_DOMAINS, excluded)
    }

    /** Исходники приложения: `src/main` и, если есть, наборы только отладочной и только release-сборки. */
    private fun sources(): List<Pair<String, String>> {
        assertTrue("Не найден каталог ${sourcesDir.absolutePath}", sourcesDir.isDirectory)
        val variantDirs = listOf("debug", "release").map { "$it/" to File("src/$it/java/ru/hznik/budget") }.filter { it.second.isDirectory }
        return (listOf("" to sourcesDir) + variantDirs).flatMap { (prefix, dir) ->
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .map { prefix + it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
                .toList()
        }.also { assertTrue("В ${sourcesDir.absolutePath} нет исходников", it.isNotEmpty()) }
    }

    private fun parse(file: File) = run {
        assertTrue("Не найден файл ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
    }

    private fun elements(document: org.w3c.dom.Document, tag: String): List<Element> = elements(document.documentElement, tag, includeSelf = true)

    private fun elements(root: Element, tag: String, includeSelf: Boolean = false): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        val found = (0 until nodes.length).map { nodes.item(it) as Element }
        return if (includeSelf && root.tagName == tag) listOf(root) + found else found
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun Node.isInside(ancestor: Node): Boolean = generateSequence(parentNode) { it.parentNode }.any { it === ancestor }

    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    private fun String.lineAt(index: Int): String {
        val start = lastIndexOf('\n', index - 1) + 1
        val end = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(start, end).trim()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
        val GIGACHAT_HOSTS = setOf("ngw.devices.sberbank.ru", "api.giga.chat")
        val RUN_LOGGED_DEFINITION = Regex("""fun <T> runLogged\(tag: String, what: String""")

        /** Домены, в которых приложение хранит данные; device_* (хранилище до разблокировки) приложение не использует. */
        val BACKUP_DOMAINS = setOf("root", "file", "database", "sharedpref", "external")

        /** Группы и артефакты SDK аналитики, рекламы и сбора сбоев. */
        val TRACKING_SDKS = Regex(
            """firebase|com\.google\.android\.gms|play-services|crashlytics|appmetrica|yandex\.mobile\.ads|mytarget|facebook|""" +
                """adjust|appsflyer|amplitude|mixpanel|sentry|admob|\bads\b|analytics""",
            RegexOption.IGNORE_CASE,
        )
    }
}
