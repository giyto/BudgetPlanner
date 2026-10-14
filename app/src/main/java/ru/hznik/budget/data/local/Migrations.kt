package ru.hznik.budget.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 → 2: периоды и обязательные платежи (этап add-period-and-daily-limit). Только новые таблицы,
 * операции и категории не меняются. SQL совпадает с app/schemas/.../2.json.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `periods` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start_date` INTEGER NOT NULL, " +
                "`next_income_date` INTEGER NOT NULL, `end_date` INTEGER, `start_amount_kopecks` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_periods_end_date` ON `periods` (`end_date`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `planned_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`amount_kopecks` INTEGER NOT NULL, `due_date` INTEGER NOT NULL, `repeat` TEXT NOT NULL, `repeat_day` INTEGER NOT NULL, `category_id` INTEGER NOT NULL, " +
                "`paid_operation_id` INTEGER, " +
                "FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`paid_operation_id`) REFERENCES `operations`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_category_id` ON `planned_payments` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_paid_operation_id` ON `planned_payments` (`paid_operation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_planned_payments_due_date` ON `planned_payments` (`due_date`)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
