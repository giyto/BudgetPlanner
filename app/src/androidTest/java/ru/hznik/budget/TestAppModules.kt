package ru.hznik.budget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.hznik.budget.ai.AiModule
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.local.CategoryDao
import ru.hznik.budget.data.local.CategoryRuleDao
import ru.hznik.budget.data.local.ChatDao
import ru.hznik.budget.data.local.DatabaseModule
import ru.hznik.budget.data.local.NotificationDao
import ru.hznik.budget.data.local.OperationDao
import ru.hznik.budget.data.local.PeriodDao
import ru.hznik.budget.data.local.PlannedPaymentDao
import ru.hznik.budget.data.local.ReconciliationDao
import ru.hznik.budget.data.local.StatisticsDao
import ru.hznik.budget.data.settings.SettingsBindings
import ru.hznik.budget.data.settings.SettingsModule
import ru.hznik.budget.data.time.TimeModule
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.ai.ToolSpec
import ru.hznik.budget.domain.lock.ElapsedClock
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockTimeout
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.reminders.AppNotification
import ru.hznik.budget.domain.reminders.AppNotifier
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.domain.repository.IncomeScheduleRepository
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.reminders.NotificationPermission
import ru.hznik.budget.reminders.RemindersBindings
import ru.hznik.budget.ui.lock.AuthAvailability
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.AuthenticatorModule
import ru.hznik.budget.ui.lock.DeviceAuthenticator
import ru.hznik.budget.ui.theme.MotionModule
import ru.hznik.budget.ui.theme.MotionSettings
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import ru.hznik.budget.domain.export.DataWipe
import ru.hznik.budget.domain.export.BackupStore
import ru.hznik.budget.domain.export.BackupCheck
import ru.hznik.budget.domain.export.AppRestarter
import ru.hznik.budget.data.backup.BackupModule

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

    /** Настройки защиты входа при запуске; по умолчанию защита выключена. */
    var lock = LockSettings()

    /** Ключ базы пропал: вместо приложения экран «Не удалось открыть данные». */
    var keyLost = false

    /** Первое открытие базы — временный сбой, повтор проходит. */
    var databaseError = false

    /**
     * Нужно ли разрешение на уведомления. По умолчанию нет: шаг онбординга «Подсказки вовремя» не мешает
     * старым тестам и не зависит от того, выдал ли установщик тестов разрешение сам.
     */
    var notificationPermissionNeeded = false

    /** В сборке есть ключ GigaChat; `false` — «Помощник недоступен в этой сборке». */
    var aiConfigured = true

    /** Анимации включены. По умолчанию нет: бесконечная анимация индикатора чата не даёт тесту дождаться покоя. */
    var animations = false

    fun reset() {
        onboarded = true
        periodStart = LocalDate.of(2026, 10, 1)
        nextIncome = LocalDate.of(2026, 10, 25)
        startAmountKopecks = 18_000_00L
        now = TEST_TODAY.atTime(12, 0)
        lock = LockSettings()
        keyLost = false
        databaseError = false
        notificationPermissionNeeded = false
        aiConfigured = true
        animations = false
        TestLlmProvider.reset()
        TestElapsedClock.now = 0
        TestBackups.reset()
    }
}

/**
 * Копия, удаление всех данных и перезапуск в UI-тестах: настоящая копия на SQLCipher проверяется в
 * `BackupRestoreTest`, здесь — только экраны.
 */
object TestBackups : BackupStore, DataWipe, AppRestarter {
    val created = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var nextCheck: BackupCheck = BackupCheck.Valid(java.time.Instant.EPOCH, backupOperations = 120, currentOperations = 5)
    /** Если задано, создание копии ждёт, пока тест его отпустит: так видно ожидание на кнопке. */
    @Volatile var createGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile var scheduled = 0
    @Volatile var discarded = 0
    @Volatile var wipes = 0
    @Volatile var restarts = 0

    override suspend fun create(target: String, password: String) {
        createGate?.await()
        created += target to password
    }

    override suspend fun check(source: String, password: String): BackupCheck = nextCheck

    override suspend fun scheduleRestore(): Boolean = (nextCheck is BackupCheck.Valid).also { if (it) scheduled++ }

    override suspend fun discard() {
        discarded++
    }

    override suspend fun schedule() {
        wipes++
    }

    override fun restart() {
        restarts++
    }

    fun reset() {
        created.clear()
        nextCheck = BackupCheck.Valid(java.time.Instant.EPOCH, backupOperations = 120, currentOperations = 5)
        createGate = null
        scheduled = 0
        discarded = 0
        wipes = 0
        restarts = 0
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [BackupModule::class])
object TestBackupModule {
    @Provides
    fun provideBackupStore(): BackupStore = TestBackups

    @Provides
    fun provideDataWipe(): DataWipe = TestBackups

    @Provides
    fun provideAppRestarter(): AppRestarter = TestBackups
}

/** Монотонные часы блокировки в UI-тестах: тест сам переводит их вперёд. */
object TestElapsedClock : ElapsedClock {
    @Volatile
    var now = 0L

    override fun elapsedMillis(): Long = now
}

/**
 * Системное окно проверки Compose-тест нажать не может, поэтому в UI-тестах проверка подменена:
 * тест задаёт доступность и ответ, а проверка возвращает его сразу.
 */
object TestAuth : DeviceAuthenticator {
    @Volatile
    var availability = AuthAvailability.AVAILABLE

    @Volatile
    var result: AuthResult = AuthResult.Success

    @Volatile
    var calls = 0

    override fun availability(): AuthAvailability = availability

    override suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): AuthResult {
        calls++
        return result
    }

    fun reset() {
        availability = AuthAvailability.AVAILABLE
        result = AuthResult.Success
        calls = 0
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AuthenticatorModule::class])
object TestAuthenticatorModule {
    @Provides
    fun provideDeviceAuthenticator(): DeviceAuthenticator = TestAuth
}

/** База в памяти без шифрования; шифрование проверяют отдельные тесты на файлах. */
@Singleton
class FakeDatabaseAccess @Inject constructor() : DatabaseAccess {
    @Volatile
    var wiped = false

    @Volatile
    var opens = 0

    override suspend fun open(): DatabaseState {
        opens++
        return when {
            TestStart.keyLost && !wiped -> DatabaseState.KEY_LOST
            TestStart.databaseError && opens == 1 -> DatabaseState.ERROR
            else -> DatabaseState.READY
        }
    }

    override suspend fun wipe() {
        wiped = true
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
    fun provideDatabaseAccess(impl: FakeDatabaseAccess): DatabaseAccess = impl

    @Provides
    fun provideCategoryDao(db: BudgetDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideOperationDao(db: BudgetDatabase): OperationDao = db.operationDao()

    @Provides
    fun providePeriodDao(db: BudgetDatabase): PeriodDao = db.periodDao()

    @Provides
    fun providePlannedPaymentDao(db: BudgetDatabase): PlannedPaymentDao = db.plannedPaymentDao()

    @Provides
    fun provideStatisticsDao(db: BudgetDatabase): StatisticsDao = db.statisticsDao()

    @Provides
    fun provideNotificationDao(db: BudgetDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideReconciliationDao(db: BudgetDatabase): ReconciliationDao = db.reconciliationDao()

    @Provides
    fun provideCategoryRuleDao(db: BudgetDatabase): CategoryRuleDao = db.categoryRuleDao()

    @Provides
    fun provideChatDao(db: BudgetDatabase): ChatDao = db.chatDao()
}

/** «Сегодня» в UI-тестах — [TestStart.now] по Москве, по умолчанию 13 октября 2026, 12:00 (вторник). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TimeModule::class])
object TestTimeModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.fixed(TestStart.now.atZone(TEST_ZONE).toInstant(), TEST_ZONE)

    @Provides
    fun provideElapsedClock(): ElapsedClock = TestElapsedClock
}

/**
 * DataStore настроек в отдельном файле на каждый тест: Hilt создаёт синглтоны заново для каждого теста,
 * а два DataStore на одном файле в одном процессе DataStore запрещает.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsModule::class])
object TestDataStoreModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test_settings_${System.nanoTime()}.preferences_pb").apply { deleteOnExit() } },
        )
}

/** Расписание дохода в памяти вместо DataStore: у каждого теста своё. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SettingsBindings::class])
abstract class TestSettingsModule {
    @Binds
    abstract fun bindIncomeScheduleRepository(impl: InMemoryIncomeScheduleRepository): IncomeScheduleRepository

    @Binds
    abstract fun bindLockSettingsRepository(impl: InMemoryLockSettingsRepository): LockSettingsRepository
}

@Singleton
class InMemoryLockSettingsRepository @Inject constructor() : LockSettingsRepository {
    val state = MutableStateFlow(TestStart.lock)

    override fun observe(): Flow<LockSettings> = state

    override suspend fun setEnabled(enabled: Boolean) = state.update { it.copy(enabled = enabled) }

    override suspend fun setTimeout(timeout: LockTimeout) = state.update { it.copy(timeout = timeout) }

    override suspend fun setHideInRecents(hide: Boolean) = state.update { it.copy(hideInRecents = hide) }

    override suspend fun setHideWidgetAmount(hide: Boolean) = state.update { it.copy(hideWidgetAmount = hide) }
}

@Singleton
class InMemoryIncomeScheduleRepository @Inject constructor() : IncomeScheduleRepository {
    private val state = MutableStateFlow<IncomeSchedule?>(if (TestStart.onboarded) IncomeSchedule.Regular(TestStart.nextIncome.dayOfMonth) else null)

    override fun observe(): Flow<IncomeSchedule?> = state

    override suspend fun set(schedule: IncomeSchedule) {
        state.value = schedule
    }
}

/** Уведомления приложения в UI-тестах: записываются, а не показываются в шторке эмулятора. */
@Singleton
class RecordingAppNotifier @Inject constructor() : AppNotifier {
    val shown = java.util.concurrent.CopyOnWriteArrayList<Pair<AppNotification, Boolean>>()

    override fun show(notification: AppNotification, private: Boolean) {
        shown += notification to private
    }
}

/** Обновления виджета в UI-тестах только считаются. */
@Singleton
class CountingWidgetRefresher @Inject constructor() : WidgetRefresher {
    val refreshes = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun refresh() {
        refreshes.incrementAndGet()
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RemindersBindings::class])
abstract class TestRemindersModule {
    @Binds
    abstract fun bindAppNotifier(impl: RecordingAppNotifier): AppNotifier

    @Binds
    abstract fun bindWidgetRefresher(impl: CountingWidgetRefresher): WidgetRefresher

    @Binds
    abstract fun bindNotificationPermission(impl: TestNotificationPermission): NotificationPermission
}

/** Разрешение на уведомления в UI-тестах задаёт [TestStart.notificationPermissionNeeded]; системный запрос не открывается. */
@Singleton
class TestNotificationPermission @Inject constructor() : NotificationPermission {
    @Volatile
    var requested = false

    override fun isRequestNeeded(): Boolean = TestStart.notificationPermissionNeeded

    override fun areEnabled(): Boolean = !TestStart.notificationPermissionNeeded

    override suspend fun wasRequested(): Boolean = requested

    override suspend fun markRequested() {
        requested = true
    }
}

/**
 * Модель ИИ в UI-тестах: ответы по очереди из [answers], без сети. Пустая очередь — ответ «ok».
 * [calls] — что приложение отправило модели: при выключенном помощнике должно быть пусто.
 */
object TestLlmProvider : LlmProvider {
    val answers = java.util.concurrent.ConcurrentLinkedQueue<LlmResult>()
    val calls = java.util.concurrent.CopyOnWriteArrayList<List<LlmMessage>>()

    /** Если задано, ответ ждёт, пока тест его отпустит: так виден индикатор «Считаю…». */
    @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override val isConfigured: Boolean get() = TestStart.aiConfigured

    override suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResult {
        calls += messages
        gate?.await()
        return answers.poll() ?: LlmResult.Text("ok", TokenUsage(10))
    }

    fun reset() {
        answers.clear()
        calls.clear()
        gate = null
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AiModule::class])
object TestAiModule {
    @Provides
    fun provideLlmProvider(): LlmProvider = TestLlmProvider
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [MotionModule::class])
object TestMotionModule {
    @Provides
    fun provideMotionSettings(): MotionSettings = object : MotionSettings {
        override fun reduced(): Boolean = !TestStart.animations
    }
}
