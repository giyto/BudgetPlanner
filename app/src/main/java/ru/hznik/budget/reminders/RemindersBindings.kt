package ru.hznik.budget.reminders

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.hznik.budget.domain.reminders.AppNotifier
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.widget.GlanceWidgetRefresher

/** Отдельный модуль: в UI-тестах уведомления и виджет подменяются записывающими заглушками. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RemindersBindings {
    @Binds
    abstract fun bindAppNotifier(impl: SystemAppNotifier): AppNotifier

    @Binds
    abstract fun bindWidgetRefresher(impl: GlanceWidgetRefresher): WidgetRefresher

    @Binds
    abstract fun bindNotificationPermission(impl: SystemNotificationPermission): NotificationPermission
}
