package ru.hznik.budget.data.demo

import ru.hznik.budget.domain.model.OperationType
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** Прошлый период демо-данных: с [start] по [end] не включая (как `end_date` у завершённого периода). */
data class DemoPeriod(val start: LocalDate, val end: LocalDate, val startAmountKopecks: Long)

/** Операция демо-данных; категория — по стабильному ключу категории по умолчанию. */
data class DemoOperation(
    val at: LocalDateTime,
    val type: OperationType,
    val categoryKey: String,
    val kopecks: Long,
    val merchant: String? = null,
    val comment: String? = null,
)

data class DemoPlan(val periods: List<DemoPeriod>, val operations: List<DemoOperation>)

/**
 * Искусственные данные для проверки статистики на глаз (только в отладочной сборке): два прошлых месяца-периода
 * перед самым ранним периодом пользователя и траты в них, а в текущем периоде — траты по вчерашний день.
 * Сегодняшний день не трогается, чтобы «можно сегодня» на главной осталось как было. Случайность с постоянным зерном:
 * одни и те же данные при каждом заполнении.
 */
object DemoDataPlan {
    private val GROCERIES = listOf("Пятёрочка", "Магнит", "Перекрёсток", "ВкусВилл")
    private val CAFES = listOf("Кофейня «Зерно»", "Шаурма у метро", "Столовая", "Пиццерия")
    private val FUN = listOf("Кинотеатр", "Боулинг", "Игры в Steam")

    fun generate(currentStart: LocalDate, earliestStart: LocalDate, today: LocalDate, seed: Int = 42): DemoPlan {
        val secondEnd = earliestStart
        val secondStart = secondEnd.minusMonths(1)
        val firstStart = secondStart.minusMonths(1)
        val periods = listOf(
            DemoPeriod(firstStart, secondStart, 18_000_00),
            DemoPeriod(secondStart, secondEnd, 20_000_00),
        )
        val random = Random(seed)
        val days = daysBetween(firstStart, secondEnd) + daysBetween(currentStart, today)
        val operations = mutableListOf<DemoOperation>()
        days.forEach { day -> operations += dayOperations(day, random) }

        // Подарок в первом периоде и возврат одежды во втором — чтобы в статистике были доходы и возвраты.
        operations += DemoOperation(firstStart.plusDays(11).atTime(15, 20), OperationType.INCOME, "gift", 2_000_00, comment = "От бабушки")
        val coat = secondStart.plusDays(6)
        operations += DemoOperation(coat.atTime(17, 40), OperationType.EXPENSE, "clothes", 4_500_00, merchant = "Спортмастер")
        operations += DemoOperation(coat.plusDays(3).atTime(12, 10), OperationType.REFUND, "clothes", 1_500_00, merchant = "Спортмастер")
        return DemoPlan(periods, operations)
    }

    private fun daysBetween(from: LocalDate, untilExclusive: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(untilExclusive) }.toList()

    private fun dayOperations(day: LocalDate, random: Random): List<DemoOperation> = buildList {
        fun at() = day.atTime(random.nextInt(8, 22), random.nextInt(0, 60))
        fun rubles(from: Int, to: Int) = random.nextInt(from, to + 1) / 10 * 10 * 100L

        if (random.nextDouble() < 0.7) add(DemoOperation(at(), OperationType.EXPENSE, "transport", rubles(50, 130), merchant = "Метро"))
        if (random.nextDouble() < 0.45) add(DemoOperation(at(), OperationType.EXPENSE, "products", rubles(150, 800), merchant = GROCERIES.random(random)))
        if (random.nextDouble() < 0.3) add(DemoOperation(at(), OperationType.EXPENSE, "cafe", rubles(150, 500), merchant = CAFES.random(random)))
        if (random.nextDouble() < 0.06) add(DemoOperation(at(), OperationType.EXPENSE, "entertainment", rubles(400, 1_500), merchant = FUN.random(random)))
        if (random.nextDouble() < 0.03) add(DemoOperation(at(), OperationType.EXPENSE, "health", rubles(200, 1_200), merchant = "Аптека"))
        if (random.nextDouble() < 0.03) add(DemoOperation(at(), OperationType.TRANSFER, "transfers", rubles(300, 1_000), comment = "Другу"))
        if (random.nextDouble() < 0.03) add(DemoOperation(at(), OperationType.EXPENSE, "other_expense", rubles(100, 500)))
        if (day.dayOfMonth == 5) add(DemoOperation(day.atTime(9, 0), OperationType.EXPENSE, "subscriptions", 299_00, merchant = "Подписка на музыку"))
        if (day.dayOfMonth == 10) add(DemoOperation(day.atTime(9, 5), OperationType.EXPENSE, "connection", 650_00, merchant = "Мобильная связь"))
    }
}
