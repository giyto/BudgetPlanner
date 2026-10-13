package ru.hznik.budget.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.hznik.budget.R

// Переменные шрифты: один файл на семейство, вес выбирается через FontVariation (API 26+).
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Onest: весь текст интерфейса. */
val Onest = FontFamily(
    variableFont(R.font.onest, FontWeight.Normal),
    variableFont(R.font.onest, FontWeight.Medium),
    variableFont(R.font.onest, FontWeight.SemiBold),
    variableFont(R.font.onest, FontWeight.Bold),
)

/** Unbounded: крупные суммы и заголовки экранов. */
val Unbounded = FontFamily(
    variableFont(R.font.unbounded, FontWeight.Medium),
    variableFont(R.font.unbounded, FontWeight.SemiBold),
    variableFont(R.font.unbounded, FontWeight.Bold),
)

private val default = Typography()

/** Размеры взяты из макетов docs/design/mockups. */
val BudgetTypography = Typography(
    // Сумма «можно сегодня» на главной карточке.
    displayLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 55.sp, letterSpacing = (-1).sp),
    // Сумма на экране новой операции.
    displayMedium = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1).sp),
    displaySmall = default.displaySmall.copy(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold),
    headlineLarge = default.headlineLarge.copy(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold),
    headlineMedium = default.headlineMedium.copy(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold),
    // Заголовок онбординга.
    headlineSmall = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 1.2.em),
    // Заголовок экрана («Бюджет»).
    titleLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    // Заголовок карточки («Период до 25 октября»).
    titleMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 1.45.em),
    bodyMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 1.4.em),
    bodySmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    // Текст на кнопках.
    labelLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    // Подписи вкладок нижней панели.
    labelMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)
