package ru.hznik.budget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import ru.hznik.budget.R
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.domain.reminders.WidgetSnapshot
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.reminders.AppAction
import ru.hznik.budget.reminders.AppActionIntents
import ru.hznik.budget.reminders.PendingAction
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import javax.inject.Inject

/** Зависимости виджета: Glance создаёт его сам, без внедрения через конструктор. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun databaseAccess(): DatabaseAccess

    fun lockSettings(): LockSettingsRepository

    /** Берётся только после проверки ключа: сценарий тянет за собой Room. */
    fun observePeriodStatus(): ObservePeriodStatus
}

/**
 * Виджет «Можно сегодня» (spec home-widget, design.md add-widget-and-reminders, решение 1): маленький 2×1 —
 * только сумма, средний 4×2 — сумма, доля лимита, дни до дохода и кнопка «+». Шрифты проекта виджету
 * недоступны (система рисует его своими), поэтому текст системным шрифтом.
 */
open class BudgetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshots = snapshots(context)
        // Первое значение до композиции: виджет не мигает «Откройте BudgetPlanner» перед суммой.
        val initial = snapshots.first()
        provideContent {
            // Поток, а не одно значение: пока сессия Glance открыта (до минуты), update() только перерисовывает
            // композицию и provideGlance не вызывается снова — сумма должна меняться и тогда.
            val snapshot by snapshots.collectAsState(initial)
            WidgetContent(snapshot)
        }
    }

    private suspend fun snapshots(context: Context): Flow<WidgetSnapshot> {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        // Процесс мог запуститься ради виджета: базу сначала нужно проверить и открыть ключом.
        val ready = runCatching { entry.databaseAccess().open() == DatabaseState.READY }.getOrDefault(false)
        if (!ready) return flowOf(WidgetSnapshot.NoData)
        return combine(entry.observePeriodStatus()(), entry.lockSettings().observe()) { status, lock -> WidgetSnapshot.of(status, lock.widgetAmountHidden) }
            .distinctUntilChanged()
            .catch { emit(WidgetSnapshot.NoData) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 40.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
    }
}

/** Карточка «Можно сегодня» 2×1 в списке виджетов. */
class BudgetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetWidget()
}

/**
 * Карточка «Можно сегодня и «+»» 4×2: тот же виджет, другой стартовый размер и превью. Отдельный класс виджета,
 * чтобы `updateAll` находил экземпляры каждой карточки.
 */
class BudgetWidgetLargeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetWidgetLarge()
}

class BudgetWidgetLarge : BudgetWidget()

/** Обновление всех виджетов на рабочем столе после изменения расчёта (решение 2). */
class GlanceWidgetRefresher @Inject constructor(@ApplicationContext private val context: Context) : WidgetRefresher {
    override suspend fun refresh() {
        runCatching { BudgetWidget().updateAll(context) }
        runCatching { BudgetWidgetLarge().updateAll(context) }
    }
}

// Цвета главной карточки из docs/design (светлая и тёмная темы).
private val heroBackground = ColorProvider(day = Color(0xFF191C1F), night = Color(0xFF23272C))
private val heroText = ColorProvider(day = Color.White, night = Color.White)
private val heroMuted = ColorProvider(day = Color(0xFFB9BEC7), night = Color(0xFFB9BEC7))
private val heroTrack = ColorProvider(day = Color(0xFF2E3338), night = Color(0xFF3A4048))
private val mint = ColorProvider(day = Color(0xFF5EE0A8), night = Color(0xFF5EE0A8))
private val overspend = ColorProvider(day = Color(0xFFFF9A62), night = Color(0xFFFF9A62))
private val primary = ColorProvider(day = Color(0xFF4B52E0), night = Color(0xFF5B62F0))

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    val medium = LocalSize.current.width >= BudgetWidget.MEDIUM.width && LocalSize.current.height >= BudgetWidget.MEDIUM.height
    val openHome = actionStartActivity(AppActionIntents.intent(context, PendingAction(AppAction.OPEN_HOME)))
    Row(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(heroBackground).clickable(openHome)
            .padding(horizontal = 16.dp, vertical = if (medium) 14.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            when (snapshot) {
                WidgetSnapshot.NoData -> Text(context.getString(R.string.widget_no_data), style = TextStyle(color = heroText, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                WidgetSnapshot.Hidden -> {
                    Label(context.getString(R.string.widget_can_spend))
                    Amount("••• ₽", heroText, medium)
                    if (medium) {
                        Spacer(GlanceModifier.height(10.dp))
                        Text(context.getString(R.string.widget_hidden_hint), style = TextStyle(color = heroMuted, fontSize = 12.sp))
                    }
                }
                is WidgetSnapshot.Shown -> ShownContent(snapshot, medium)
            }
        }
        // «+» только в среднем: в маленьком места хватает лишь на сумму (макет Widgets).
        if (medium && snapshot != WidgetSnapshot.NoData) {
            Spacer(GlanceModifier.width(12.dp))
            AddButton(actionStartActivity(AppActionIntents.intent(context, PendingAction(AppAction.ADD_EXPENSE))))
        }
    }
}

@Composable
private fun ShownContent(snapshot: WidgetSnapshot.Shown, medium: Boolean) {
    val context = LocalContext.current
    Label(context.getString(R.string.widget_can_spend))
    Amount(MoneyFormat.balance(snapshot.canSpendKopecks), if (snapshot.overspent) overspend else heroText, medium)
    if (!medium) return
    Spacer(GlanceModifier.height(10.dp))
    LinearProgressIndicator(
        progress = snapshot.spentFraction,
        modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
        color = if (snapshot.overspent) overspend else mint,
        backgroundColor = heroTrack,
    )
    Spacer(GlanceModifier.height(10.dp))
    val days = context.getString(R.string.home_days_left, snapshot.daysLeft.toInt(), SpokenMoney.plural(snapshot.daysLeft, "день", "дня", "дней"))
    Text(
        context.getString(R.string.widget_of_limit, MoneyFormat.amount(snapshot.dailyLimitKopecks), days),
        style = TextStyle(color = heroMuted, fontSize = 13.sp),
        maxLines = 1,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = TextStyle(color = heroMuted, fontSize = 12.sp), maxLines = 1)
}

@Composable
private fun Amount(text: String, color: androidx.glance.unit.ColorProvider, medium: Boolean) {
    Text(text, style = TextStyle(color = color, fontSize = if (medium) 34.sp else 24.sp, fontWeight = FontWeight.Bold), maxLines = 1)
}

/** Кнопка «+» 56 dp — больше минимальных 44 dp; TalkBack читает «Добавить трату». */
@Composable
private fun AddButton(onClick: Action) {
    val description = LocalContext.current.getString(R.string.widget_add_expense)
    Box(
        modifier = GlanceModifier.size(56.dp).cornerRadius(18.dp).background(primary).clickable(onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(provider = ImageProvider(R.drawable.ic_widget_add), contentDescription = null, modifier = GlanceModifier.size(28.dp))
    }
}
