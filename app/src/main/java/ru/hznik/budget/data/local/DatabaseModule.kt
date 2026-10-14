package ru.hznik.budget.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.hznik.budget.data.repository.RoomCategoryRepository
import ru.hznik.budget.data.repository.RoomOperationRepository
import ru.hznik.budget.data.repository.RoomPeriodRepository
import ru.hznik.budget.data.repository.RoomPlannedPaymentRepository
import ru.hznik.budget.data.repository.RoomStatisticsRepository
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.repository.StatisticsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** Фабрика открытия базы. На этапе add-data-protection её заменит SQLCipher (SupportOpenHelperFactory). */
    @Provides
    fun provideOpenHelperFactory(): SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        openHelperFactory: SupportSQLiteOpenHelper.Factory,
    ): BudgetDatabase = Room.databaseBuilder(context, BudgetDatabase::class.java, BudgetDatabase.NAME)
        .openHelperFactory(openHelperFactory)
        .addCallback(BudgetDatabase.SeedCallback)
        .addMigrations(*ALL_MIGRATIONS)
        .build()

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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindOperationRepository(impl: RoomOperationRepository): OperationRepository

    @Binds
    abstract fun bindCategoryRepository(impl: RoomCategoryRepository): CategoryRepository

    @Binds
    abstract fun bindPeriodRepository(impl: RoomPeriodRepository): PeriodRepository

    @Binds
    abstract fun bindPlannedPaymentRepository(impl: RoomPlannedPaymentRepository): PlannedPaymentRepository

    @Binds
    abstract fun bindStatisticsRepository(impl: RoomStatisticsRepository): StatisticsRepository
}
