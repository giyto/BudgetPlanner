package ru.hznik.budget.domain.notifications.parser

import ru.hznik.budget.domain.notifications.SupportedBanks

/**
 * Парсер уведомлений Сбера (design.md add-notification-import, решение 3).
 *
 * Предварительная версия по известным форматам push и SMS Сбера: «СЧЁТ1234 14:05 Покупка 450р PYATEROCHKA
 * Баланс: 12 300р», «Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300,50 ₽», «Перевод 500 ₽ Олег К.»,
 * «Зачисление 9 000 ₽». Правится по настоящим образцам с телефона пользователя (tasks.md, 6.1).
 */
class SberParser : RuBankParser(SupportedBanks.SBER)
