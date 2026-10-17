package ru.hznik.budget.ui.theme

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Приложение читает ту же настройку, что ставят «Убрать анимацию» и длительность анимаций 0 (spec ui-motion
 * «Системная настройка «Убрать анимацию»», задача 6.2). Запускается на эмуляторе дважды: со шкалой 0 и 1.
 */
@RunWith(AndroidJUnit4::class)
class SystemMotionSettingsTest {
    @Test
    fun reducedFollowsAnimatorDurationScale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertEquals("шкала анимаций $scale", scale == 0f, SystemMotionSettings(context).reduced())
    }
}
