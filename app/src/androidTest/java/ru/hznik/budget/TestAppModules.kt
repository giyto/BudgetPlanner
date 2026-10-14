package ru.hznik.budget

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.local.CategoryDao
import ru.hznik.budget.data.local.DatabaseModule
import ru.hznik.budget.data.local.OperationDao
import ru.hznik.budget.data.local.PeriodDao
import ru.hznik.budget.data.local.PlannedPaymentDao
import ru.hznik.budget.data.settings.SettingsBindings
import ru.hznik.budget.data.time.TimeModule
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.repository.IncomeScheduleRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

val TEST_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
val TEST_TODAY: LocalDate = LocalDate.of(2026, 10, 13)

/**
 * С чего начинается приложение в UI-тесте. По умолчанию онбординг пройден: период с 1 по 25 октября
 * на 18 000 ₽ и доход каждый месяц 25 числа, «сейчас» — 13 октября 12:00, часы стоят.
 * Тест меняет поля в конструкторе (раньше, чем Hilt создаст базу и часы), [FlowTestBase] сбрасывает их после теста.
 */
object TestStart {
    var onboarded = true
    var periodStart: LocalDate = LocalDate.of(2026, 10, 1)
    var nextIncome: LocalDate = LocalDate.of(2026, 10, 25)
    var startAmountKopecks = 18_000_00L
    var now: LocalDateTime = TEST_TODAY.atTime(12, 0)

    /** Часы идут в реальном времени от [now]: так проверяется смена дня в полночь. */
    var clockRuns = false

    fun reset() {
        onboarded = true
        periodStart = LocalDate.of(2026, 10, 1)
        nextIncome = LocalDate.of(2026, 10, 25)
        startAmountKopecks = 18_000_00L
        now = TEST_TODAY.atTime(12, 0)
        clockRuns = false
    }
}

/** В UI-тестах база в памяти: у каждого теста своя, с категориями по умолчанию и периодом из [TestStart]. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BudgetDatabase =
        Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback)
            .addCallback(PeriodSeedCallback)
            .build()

    private object PeriodSeedCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            if (!TestStart.onboarded) return
            db.execSQL(
                "INSERT INTO periods (start_date, next_income_date, end_date, start_amount_kopecks) VALUES (?, ?, NULL, ?)",
                arrayOf<Any>(TestStart.periodStart.toEpochDay(), TestStart.nextIncome.toEpochDay(), TestStart.startAmountKopecks),
            )
        }
    }

    @Provides
    fun provideCategoryDao(db: BudgetDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideOperationDao(db: BudgetDatabase): OperationDao = db.operationDao()

    @Provides
    fun providePeriodDao(db: BudgetDatabase): PeriodDao = db.periodDao()

    @Provides
    fun providePlannedPaymentDao(db: BudgetDatabase): PlannedPaymentDao = db.plannedPaymentDao()
}

/** «Сегодня» в UI-тестах — [TestStart.now] по Москве, по умолчанию 13 октября 2026, 12:00 (вторник). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TimeModule::class])
object TestTimeModule {
    @Provides
    @Singleton
    fun provideClock(): Clock {
        val start = TestStart.now.atZone(TEST_ZONE).toInstant()
        return if (TestStart.clockRuns) RunningClock(start, TEST_ZONE) else Clock.fixed(start, TEST_ZONE)
    }
}

/** Часы, которые идут от [start] вместе с монотонным временем устройства. */
private class RunningClock(private val start: Instant, private val zone: ZoneId) : Clock() {
    private val startedAt = SystemClock.elapsedRealtime()

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = RunningClock(start, zone)

    override fun instant(): Instant = start.plusMillis(SystemClock.elapsedRealtime() - startedAt)
}

/** Расписание дохода в памяти вместо DataStore: у каждого теста своё. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsBindings::class])
abstract class TestSettingsModule {
    @Binds
    abstract fun bindIncomeScheduleRepository(impl: InMemoryIncomeScheduleRepository): IncomeScheduleRepository
}

@Singleton
class InMemoryIncomeScheduleRepository @Inject constructor() : IncomeScheduleRepository {
    private val state = MutableStateFlow<IncomeSchedule?>(if (TestStart.onboarded) IncomeSchedule.Regular(TestStart.nextIncome.dayOfMonth) else null)

    override fun observe(): Flow<IncomeSchedule?> = state

    override suspend fun set(schedule: IncomeSchedule) {
        state.value = schedule
    }
}
