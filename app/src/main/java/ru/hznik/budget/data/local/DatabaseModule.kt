package ru.hznik.budget.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ru.hznik.budget.data.repository.RoomCategoryRepository
import ru.hznik.budget.data.repository.RoomOperationRepository
import ru.hznik.budget.data.repository.RoomPeriodRepository
import ru.hznik.budget.data.repository.RoomPlannedPaymentRepository
import ru.hznik.budget.data.repository.RoomStatisticsRepository
import ru.hznik.budget.data.security.DatabaseEncryption
import ru.hznik.budget.data.security.DatabaseKeyStore
import ru.hznik.budget.data.security.EncryptedDatabaseAccess
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.repository.StatisticsRepository
import ru.hznik.budget.domain.security.DatabaseAccess
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabaseEncryption(@ApplicationContext context: Context): DatabaseEncryption = DatabaseEncryption(
        dbFile = context.getDatabasePath(BudgetDatabase.NAME),
        keyStore = DatabaseKeyStore(File(context.noBackupFilesDir, DatabaseKeyStore.FILE_NAME)),
    )

    @Provides
    fun provideDatabaseAccess(impl: EncryptedDatabaseAccess): DatabaseAccess = impl

    /** База зашифрована SQLCipher; ключ готов после `DatabaseAccess.open()`, до этого к Room не обращаются. */
    @Provides
    fun provideOpenHelperFactory(encryption: DatabaseEncryption): SupportSQLiteOpenHelper.Factory =
        SupportOpenHelperFactory(encryption.passphrase())

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
