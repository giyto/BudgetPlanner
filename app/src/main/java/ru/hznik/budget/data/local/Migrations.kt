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

/**
 * 2 → 3: автоимпорт из уведомлений (этап add-notification-import). У операций — признак черновика, банк, карта,
 * ключ уведомления и признак «предложить новый период»; таблицы остатков, ключей уведомлений и образцов.
 * SQL совпадает с app/schemas/.../3.json.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `is_draft` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `bank_id` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `card_last4` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `notification_key` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `suggests_new_period` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_is_draft` ON `operations` (`is_draft`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_operations_notification_key` ON `operations` (`notification_key`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bank_balances` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bank_id` TEXT NOT NULL, " +
                "`card_last4` TEXT, `balance_kopecks` INTEGER NOT NULL, `observed_at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bank_balances_bank_id_card_last4_observed_at` ON `bank_balances` (`bank_id`, `card_last4`, `observed_at`)",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `notification_keys` (`key` TEXT NOT NULL, `bank_id` TEXT NOT NULL, `seen_at` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_keys_seen_at` ON `notification_keys` (`seen_at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notification_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bank_id` TEXT NOT NULL, " +
                "`posted_at` INTEGER NOT NULL, `text` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_samples_posted_at` ON `notification_samples` (`posted_at`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_samples_bank_id_posted_at_text` ON `notification_samples` (`bank_id`, `posted_at`, `text`)",
        )
    }
}

/** 3 → 4: переводы между своими счетами — исходный тип и вторая сторона пары. SQL совпадает с app/schemas/.../4.json. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `original_type` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `counter_bank_id` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `counter_card_last4` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `counter_notification_key` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `counter_merchant` TEXT")
        db.execSQL("ALTER TABLE `operations` ADD COLUMN `counter_occurred_at` INTEGER")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
