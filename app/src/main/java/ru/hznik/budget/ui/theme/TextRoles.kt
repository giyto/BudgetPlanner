package ru.hznik.budget.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Повторяющиеся роли текста из макетов (классы .btn, .btn2, .t1, .t2, .group). Стиль задан целиком, вместе с
 * межстрочным интервалом: у Text с одним fontSize интервал наследуется от bodyLarge (22 sp), и перенесённая на
 * несколько строк мелкая подсказка выглядит разреженной. Стили Material в Type.kt не меняются: labelLarge и
 * остальные используются кнопками и полями ввода.
 */
object TextRoles {
    /** Текст главной кнопки внизу экрана (.btn, 17 px). */
    val buttonLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 20.sp)

    /** Текст второстепенной кнопки и кнопки в плашке (.btn2, 16 px); совпадает с labelLarge. */
    val button = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)

    /** Заголовок строки, плашки или карточки (.t1, 16 px). Интервал прежний, чтобы высота строк не изменилась. */
    val itemTitle = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)

    /** Подпись под заголовком и подсказка (.t2, 13 px) с плотным интервалом для текста в несколько строк. */
    val hint = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

    /** Заголовок группы в настройках (.group, 14 px). Интервал прежний: заголовок всегда в одну строку. */
    val groupHeader = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp)
}
