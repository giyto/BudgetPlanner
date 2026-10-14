package ru.hznik.budget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CategoryEntity::class, OperationEntity::class], version = 1, exportSchema = true)
abstract class BudgetDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao

    abstract fun operationDao(): OperationDao

    /** Заполняет категории по умолчанию в той же транзакции, что и создание схемы. */
    object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.insertDefaultCategories()
        }
    }

    companion object {
        const val NAME = "budget.db"
    }
}
