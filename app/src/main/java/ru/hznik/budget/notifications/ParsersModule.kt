package ru.hznik.budget.notifications

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.hznik.budget.domain.notifications.parser.AlfaParser
import ru.hznik.budget.domain.notifications.parser.BankNotificationParser
import ru.hznik.budget.domain.notifications.parser.SberParser
import ru.hznik.budget.domain.notifications.parser.TBankParser

/** Парсеры банков; новый банк — ещё одна привязка `@IntoSet` (FR-05). */
@Module
@InstallIn(SingletonComponent::class)
object ParsersModule {
    @Provides
    @IntoSet
    fun provideSberParser(): BankNotificationParser = SberParser()

    @Provides
    @IntoSet
    fun provideAlfaParser(): BankNotificationParser = AlfaParser()

    @Provides
    @IntoSet
    fun provideTBankParser(): BankNotificationParser = TBankParser()
}
