package ru.hznik.budget.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** Шаги включения автоимпорта (spec notification-import, «Включение автоимпорта по шагам»). */
class AutoImportSetupTest {
    private fun facts(
        sdk: Int = 34,
        manufacturer: String = "Google",
        fromFile: Boolean = false,
        granted: Boolean = false,
        attempted: Boolean = false,
        restrictedVisited: Boolean = false,
        autostartDone: Boolean = false,
    ) = SetupFacts(sdk, manufacturer, fromFile, granted, attempted, restrictedVisited, autostartDone)

    @Test
    fun `pixel from store needs only notification access`() {
        assertEquals(SetupStep.NOTIFICATION_ACCESS, AutoImportSetup.nextStep(facts()))
        assertEquals(SetupStep.DONE, AutoImportSetup.nextStep(facts(granted = true)))
    }

    @Test
    fun `installed from file on android 13 plus offers restricted settings after the first attempt`() {
        assertEquals(SetupStep.NOTIFICATION_ACCESS, AutoImportSetup.nextStep(facts(sdk = 33, fromFile = true)))
        assertEquals(SetupStep.RESTRICTED_SETTINGS, AutoImportSetup.nextStep(facts(sdk = 33, fromFile = true, attempted = true)))
        assertEquals(SetupStep.NOTIFICATION_ACCESS, AutoImportSetup.nextStep(facts(sdk = 33, fromFile = true, attempted = true, restrictedVisited = true)))
        assertEquals(SetupStep.DONE, AutoImportSetup.nextStep(facts(sdk = 33, fromFile = true, granted = true)))
    }

    @Test
    fun `no restricted settings before android 13`() {
        assertEquals(SetupStep.NOTIFICATION_ACCESS, AutoImportSetup.nextStep(facts(sdk = 32, fromFile = true, attempted = true)))
        assertEquals(SetupStep.NOTIFICATION_ACCESS, AutoImportSetup.nextStep(facts(sdk = 29, fromFile = true)))
    }

    @Test
    fun `xiaomi asks for autostart after access`() {
        assertEquals(SetupStep.NOTIFICATION_ACCESS, AutoImportSetup.nextStep(facts(manufacturer = "Xiaomi")))
        assertEquals(SetupStep.AUTOSTART, AutoImportSetup.nextStep(facts(manufacturer = "Xiaomi", granted = true)))
        assertEquals(SetupStep.DONE, AutoImportSetup.nextStep(facts(manufacturer = "Xiaomi", granted = true, autostartDone = true)))
    }

    @Test
    fun `aggressive firmwares are recognised case-insensitively`() {
        listOf("Xiaomi", "Redmi", "POCO", "HUAWEI", "HONOR", "OPPO", "realme", "OnePlus", "vivo", "samsung").forEach {
            assertEquals(it, true, AutoImportSetup.needsAutostart(it))
        }
        listOf("Google", "motorola", "Nothing").forEach { assertEquals(it, false, AutoImportSetup.needsAutostart(it)) }
    }
}
