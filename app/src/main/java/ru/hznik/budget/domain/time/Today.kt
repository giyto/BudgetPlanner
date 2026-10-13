package ru.hznik.budget.domain.time

import java.time.Clock
import java.time.LocalDate

/** Текущая дата в часовом поясе часов. Весь код, которому нужно «сегодня», берёт его отсюда. */
fun Clock.today(): LocalDate = LocalDate.now(this)
