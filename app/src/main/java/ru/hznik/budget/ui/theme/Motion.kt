package ru.hznik.budget.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Выключены ли анимации в системе (spec ui-motion «Системная настройка «Убрать анимацию»»). */
interface MotionSettings {
    fun reduced(): Boolean
}

/**
 * «Убрать анимацию» в специальных возможностях и длительность анимаций 0 в настройках разработчика ставят
 * `ANIMATOR_DURATION_SCALE` в 0 (design.md add-animations, решение 1).
 */
class SystemMotionSettings @Inject constructor(@ApplicationContext private val context: Context) : MotionSettings {
    override fun reduced(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Отдельный модуль: в UI-тестах анимации по умолчанию выключены. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MotionModule {
    @Binds
    abstract fun bindMotionSettings(impl: SystemMotionSettings): MotionSettings
}

/** `true` — показывать изменения сразу, без анимации. Задаётся в MainActivity, перечитывается при возврате. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Длительности движения в одном месте (design.md, решение 2). */
object Motion {
    const val DIGITS = 300
    const val BAR = 450
    const val CHART = 600
    const val ITEM = 250
    const val SCREEN = 300
    const val TAB = 200
    const val PRESS = 150
}

/** Плавное изменение заданной длительности или мгновенное, если анимации выключены в системе. */
@Composable
@ReadOnlyComposable
fun <T> motionTween(durationMillis: Int): AnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(durationMillis, easing = FastOutSlowInEasing)
