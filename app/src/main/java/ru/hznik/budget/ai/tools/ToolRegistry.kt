package ru.hznik.budget.ai.tools

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.ToolSpec
import javax.inject.Inject

/**
 * Какие инструменты доступны модели сейчас (spec ai-assistant «Не отправлять названия магазинов»):
 * при скрытых названиях инструмента магазинов нет вовсе, и вызвать его по имени тоже нельзя.
 */
class ToolRegistry @Inject constructor(
    private val tools: Set<@JvmSuppressWildcards AssistantTool>,
    private val settings: AiSettings,
) {
    suspend fun available(): List<AssistantTool> {
        val hideMerchants = settings.hideMerchants.first()
        return tools.filter { !hideMerchants || !it.sendsMerchants }.sortedBy { it.name }
    }

    suspend fun specs(): List<ToolSpec> = available().map { ToolSpec(it.name, it.description, it.parametersSchema) }

    /** Результат инструмента JSON-строкой; ошибка — `{"error": "..."}`, чтобы модель могла исправить вызов. */
    suspend fun run(name: String, argumentsJson: String): String = call(name, argumentsJson).json

    /** То же с признаком успеха: подпись «Посчитал по вашему бюджету» — только если инструмент действительно сработал. */
    suspend fun call(name: String, argumentsJson: String): ToolOutput {
        val tool = available().firstOrNull { it.name == name } ?: return error("Инструмента $name нет")
        val args = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        return try {
            ToolOutput(tool.run(args).toString(), ok = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            error(e.message ?: "Ошибка")
        } catch (e: Exception) {
            error("Не удалось посчитать")
        }
    }

    private fun error(message: String) = ToolOutput(buildJsonObject { put("error", message) }.toString(), ok = false)
}

/** Результат инструмента для модели и признак, что расчёт прошёл. */
data class ToolOutput(val json: String, val ok: Boolean)

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolsModule {
    @Binds @IntoSet
    abstract fun periodStatus(tool: PeriodStatusTool): AssistantTool

    @Binds @IntoSet
    abstract fun spending(tool: SpendingTool): AssistantTool

    @Binds @IntoSet
    abstract fun spendingByCategory(tool: SpendingByCategoryTool): AssistantTool

    @Binds @IntoSet
    abstract fun topMerchants(tool: TopMerchantsTool): AssistantTool

    @Binds @IntoSet
    abstract fun simulatePurchase(tool: SimulatePurchaseTool): AssistantTool

    @Binds @IntoSet
    abstract fun comparePeriods(tool: ComparePeriodsTool): AssistantTool
}
