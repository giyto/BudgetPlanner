package ru.hznik.budget.data.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Системные часы, которые читают часовой пояс телефона при каждом обращении.
 * В отличие от [Clock.systemDefaultZone], смена пояса учитывается сразу, без перезапуска процесса.
 */
class SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun equals(other: Any?): Boolean = other is SystemZoneClock

    override fun hashCode(): Int = SystemZoneClock::class.hashCode()

    override fun toString(): String = "SystemZoneClock"
}
