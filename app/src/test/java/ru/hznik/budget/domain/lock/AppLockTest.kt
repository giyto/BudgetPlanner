package ru.hznik.budget.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила блокировки (spec app-lock, «Блокировка после ухода в фон»). */
class AppLockTest {
    private var now = 0L
    private val lock = AppLock { now }

    private fun enabled(timeout: LockTimeout = LockTimeout.ONE_MINUTE) = LockSettings(enabled = true, timeout = timeout)

    private fun backgroundFor(millis: Long) {
        lock.onBackground()
        now += millis
        lock.onForeground()
    }

    @Test
    fun `state is unknown until settings are read`() {
        assertEquals(LockState.UNKNOWN, lock.state.value)
    }

    @Test
    fun `cold start is locked when protection is on and unlocked when off`() {
        lock.onSettings(enabled())
        assertEquals(LockState.LOCKED, lock.state.value)

        val other = AppLock { 0L }
        other.onSettings(LockSettings())
        assertEquals(LockState.UNLOCKED, other.state.value)
    }

    @Test
    fun `successful check unlocks and cancelled check does not`() {
        lock.onSettings(enabled())
        lock.onAuthenticationStarted()
        lock.onAuthenticationFinished(success = false)
        assertEquals(LockState.LOCKED, lock.state.value)
        lock.onAuthenticationStarted()
        lock.onAuthenticationFinished(success = true)
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `back after 40 seconds stays unlocked, after 3 minutes locks`() {
        lock.onSettings(enabled())
        lock.onAuthenticationFinished(success = true)

        backgroundFor(40_000)
        assertEquals(LockState.UNLOCKED, lock.state.value)

        backgroundFor(3 * 60_000)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `immediately locks on any return`() {
        lock.onSettings(enabled(LockTimeout.IMMEDIATELY))
        lock.onAuthenticationFinished(success = true)
        backgroundFor(0)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `exactly the timeout locks`() {
        lock.onSettings(enabled(LockTimeout.FIVE_MINUTES))
        lock.onAuthenticationFinished(success = true)
        backgroundFor(LockTimeout.FIVE_MINUTES.millis - 1)
        assertEquals(LockState.UNLOCKED, lock.state.value)
        backgroundFor(LockTimeout.FIVE_MINUTES.millis)
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `system check window does not count as leaving the app`() {
        lock.onSettings(enabled(LockTimeout.IMMEDIATELY))
        lock.onAuthenticationStarted()
        // Ввод PIN в отдельном окне системы: процесс уходит в фон и возвращается.
        backgroundFor(5_000)
        lock.onAuthenticationFinished(success = true)
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `leaving the app while a check from settings is open still counts after a cancel`() {
        lock.onSettings(enabled())
        lock.onAuthenticationFinished(success = true)
        // В настройках открыто окно проверки, пользователь ушёл, система закрыла окно уже после ухода.
        lock.onAuthenticationStarted()
        lock.onBackground()
        lock.onAuthenticationFinished(success = false)
        now += 3 * 60_000
        lock.onForeground()
        assertEquals(LockState.LOCKED, lock.state.value)
    }

    @Test
    fun `enabling protection while using the app does not lock right away`() {
        lock.onSettings(LockSettings())
        lock.onSettings(enabled())
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `disabling protection unlocks and stops timeout locking`() {
        lock.onSettings(enabled())
        lock.onSettings(LockSettings(enabled = false))
        assertEquals(LockState.UNLOCKED, lock.state.value)
        backgroundFor(10 * 60_000)
        assertEquals(LockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `screen lock removed unlocks and raises a one-time notice`() {
        lock.onSettings(enabled())
        lock.onScreenLockRemoved()
        assertEquals(LockState.UNLOCKED, lock.state.value)
        assertTrue(lock.screenLockRemovedNotice.value)
        lock.consumeNotice()
        assertFalse(lock.screenLockRemovedNotice.value)
    }
}
