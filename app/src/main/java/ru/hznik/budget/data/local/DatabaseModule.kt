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
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
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
        .build()

    @Provides
    fun provideCategoryDao(db: BudgetDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideOperationDao(db: BudgetDatabase): OperationDao = db.operationDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindOperationRepository(impl: RoomOperationRepository): OperationRepository

    @Binds
    abstract fun bindCategoryRepository(impl: RoomCategoryRepository): CategoryRepository
}
