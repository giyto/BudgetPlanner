package ru.hznik.budget.data.time

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.hznik.budget.domain.lock.ElapsedClock
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = SystemZoneClock()

    /** Время с загрузки телефона: не меняется, когда пользователь переводит часы. */
    @Provides
    fun provideElapsedClock(): ElapsedClock = ElapsedClock { SystemClock.elapsedRealtime() }
}
