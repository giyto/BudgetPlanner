package ru.hznik.budget.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomChatRepository
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRole
import java.time.Instant

/** Миграция 6 → 7 и история диалога с помощником на настоящей базе (add-ai-assistant, задача 2.3). */
@RunWith(AndroidJUnit4::class)
class ChatDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    private val now = Instant.parse("2026-10-17T09:00:00Z")
    private lateinit var db: BudgetDatabase
    private lateinit var repo: RoomChatRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback).build()
        repo = RoomChatRepository(db.chatDao())
    }

    @After
    fun close() = db.close()

    @Test
    fun migrationFrom6AddsChatTableAndKeepsRules() {
        val name = "migration-6-7.db"
        migrationHelper.createDatabase(name, 6).use { v6 ->
            v6.execSQL("INSERT INTO reconciliation_accounts (bank_id, card_last4, anchor_kopecks, anchor_at) VALUES ('alfa', '1234', 100, 1)")
        }
        migrationHelper.runMigrationsAndValidate(name, 7, true, MIGRATION_6_7).use { v7 ->
            v7.query("SELECT count(*) FROM reconciliation_accounts").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v7.query("SELECT count(*) FROM chat_messages").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test
    fun messagesKeepOrderAndToolsAndNewDialogClearsAll() = runTest {
        repo.add(ChatMessage(role = ChatRole.USER, text = "Хватит ли до стипендии?", createdAt = now))
        repo.add(ChatMessage(role = ChatRole.ASSISTANT, text = "Хватит", tools = listOf("simulate_purchase", "get_period_status"), createdAt = now.plusSeconds(3)))
        val errorId = repo.add(ChatMessage(role = ChatRole.ERROR, text = "NO_NETWORK", createdAt = now.plusSeconds(5)))

        val all = repo.observe().first()
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.ERROR), all.map { it.role })
        assertEquals(listOf("simulate_purchase", "get_period_status"), all[1].tools)
        assertTrue(all[0].tools.isEmpty())
        assertEquals(now.plusSeconds(3), all[1].createdAt)

        // «Повторить» убирает сообщение об ошибке.
        repo.delete(errorId)
        assertEquals(2, repo.observe().first().size)

        repo.clear()
        assertTrue(repo.observe().first().isEmpty())
    }
}
