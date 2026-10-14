package ru.hznik.budget

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.local.CategoryDao
import ru.hznik.budget.data.local.DatabaseModule
import ru.hznik.budget.data.local.OperationDao
import ru.hznik.budget.data.time.TimeModule
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Singleton

val TEST_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
val TEST_TODAY: LocalDate = LocalDate.of(2026, 10, 13)

/** В UI-тестах база в памяти: у каждого теста своя, с категориями по умолчанию. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BudgetDatabase =
        Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback)
            .build()

    @Provides
    fun provideCategoryDao(db: BudgetDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideOperationDao(db: BudgetDatabase): OperationDao = db.operationDao()
}

/** «Сегодня» в UI-тестах — 13 октября 2026, 12:00 по Москве (вторник). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TimeModule::class])
object TestTimeModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.fixed(TEST_TODAY.atTime(12, 0).atZone(TEST_ZONE).toInstant(), TEST_ZONE)
}
