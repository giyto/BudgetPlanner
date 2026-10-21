package ru.hznik.budget.devtools

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.SampleSettings
import java.time.Instant

// Release-сборка без инструментов разработки (задача 9.2 harden-and-release-v1): те же объявления, что в src/debug,
// но пустые. Раздела «Для разработки» и экрана образцов нет, образцы не записываются, даже если флаг режима
// остался в настройках. Старые образцы в базе по-прежнему удаляет NotificationIntake.purgeExpired.

/** Раздела «Для разработки» в release нет. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DevSettingsSection(navController: NavController) = Unit

/** Экранов разработки в release нет. */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun NavGraphBuilder.devRoutes(navController: NavController) = Unit

/** Режима образцов в release нет: уведомление не сохраняется. */
@Suppress("UNUSED_PARAMETER")
suspend fun recordNotificationSample(settings: SampleSettings, repository: NotificationRepository, bankId: String, postedAt: Instant, text: String) = Unit

@Module
@InstallIn(SingletonComponent::class)
object DevToolsModule {
    /** Файл образцов создаёт только отладочная сборка: удалять нечего. */
    @Provides
    fun provideDevFiles(): DevFiles = object : DevFiles {
        override suspend fun deleteAll() = Unit
    }
}
