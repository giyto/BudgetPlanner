package ru.hznik.budget.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Подбор размера, при котором текст помещается **без разрыва слов**: стандартный `TextAutoSize.StepBased` считает
 * подпись поместившейся, даже если слово разорвано по буквам («Продукт-ы»). Размер уменьшается шагами от
 * [maxFontSize], пока строки не будут кончаться на границе слов, но не меньше [minFontSize] (spec app-theme
 * «Основные экраны при крупном шрифте»). При обычном шрифте подпись остаётся размера [maxFontSize].
 */
data class WholeWordsAutoSize(val minFontSize: TextUnit, val maxFontSize: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = maxFontSize.value
        while (size > minFontSize.value) {
            if (performLayout(constraints, text, size.sp).fits()) return size.sp
            size -= STEP
        }
        return minFontSize
    }

    private fun TextLayoutResult.fits(): Boolean {
        if (didOverflowWidth || didOverflowHeight) return false
        if (lineCount > 0 && isLineEllipsized(lineCount - 1)) return false
        val text = layoutInput.text.text
        // Строка, кроме последней, должна кончаться пробелом или стоять перед ним: иначе слово разорвано.
        return (0 until lineCount - 1).all { line ->
            val end = getLineEnd(line)
            text.getOrNull(end - 1)?.isWhitespace() == true || text.getOrNull(end)?.isWhitespace() == true
        }
    }

    private companion object {
        const val STEP = 0.5f
    }
}
