package ru.hznik.budget.devtools

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.SampleMasker
import ru.hznik.budget.domain.notifications.SampleSettings
import ru.hznik.budget.ui.settings.DemoDataSection
import ru.hznik.budget.ui.settings.NotificationSamplesRoute
import ru.hznik.budget.ui.settings.SamplesFile
import java.time.Instant

// Инструменты разработки (задача 9.2 harden-and-release-v1): демо-данные, сбор образцов уведомлений и экран образцов
// есть только в отладочной сборке. Такие же объявления с пустой реализацией лежат в src/release, общий код вызывает их,
// не зная о сборке, и в release-файл не попадает ни одного класса раздела «Для разработки».

/** Образцы уведомлений банков (раздел «Для разработки»). */
@Serializable data object NotificationSamples

/** Раздел «Для разработки» в конце настроек: демо-данные и режим образцов. */
@Composable
fun DevSettingsSection(navController: NavController) {
    DemoDataSection(onOpenSamples = dropUnlessResumed { navController.navigate(NotificationSamples) })
}

/** Экраны раздела «Для разработки» в общем графе навигации. */
fun NavGraphBuilder.devRoutes(navController: NavController) {
    composable<NotificationSamples> { NotificationSamplesRoute(onBack = dropUnlessResumed { navController.popBackStack() }) }
}

/**
 * Замаскированный образец уведомления банка, если включён режим образцов (design.md add-notification-import, решение 2).
 * Повтор того же уведомления не дублируется: это проверяет [NotificationRepository.addSample].
 */
suspend fun recordNotificationSample(settings: SampleSettings, repository: NotificationRepository, bankId: String, postedAt: Instant, text: String) {
    if (settings.enabled.first()) repository.addSample(bankId, postedAt, SampleMasker.mask(text))
}

@Module
@InstallIn(SingletonComponent::class)
object DevToolsModule {
    /** Файл образцов `Download/budget-samples.txt`, который «Удалить все данные» удаляет вместе с данными. */
    @Provides
    fun provideDevFiles(samples: SamplesFile): DevFiles = object : DevFiles {
        override suspend fun deleteAll() = samples.delete()
    }
}
