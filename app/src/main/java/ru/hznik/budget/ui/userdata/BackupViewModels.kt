package ru.hznik.budget.ui.userdata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.common.runLogged
import ru.hznik.budget.devtools.DevFiles
import ru.hznik.budget.domain.export.AppRestarter
import ru.hznik.budget.domain.export.BackupCheck
import ru.hznik.budget.domain.export.BackupStore
import ru.hznik.budget.domain.export.DataWipe
import ru.hznik.budget.domain.export.UserFileInfo
import ru.hznik.budget.domain.export.UserFiles
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.ui.Notices
import ru.hznik.budget.ui.launchSave
import java.time.Clock
import javax.inject.Inject

/** Пароль копии не короче 8 символов (spec data-export «Резервная копия»). */
const val MIN_PASSWORD_LENGTH = 8

/** Экран резервной копии: пароль дважды; пароли хранятся только пока открыт экран. */
data class BackupUiState(val password: String = "", val repeat: String = "", val saving: Boolean = false) {
    val tooShort: Boolean get() = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val differ: Boolean get() = repeat.isNotEmpty() && repeat != password
    val matches: Boolean get() = password.length >= MIN_PASSWORD_LENGTH && repeat == password
    val canSave: Boolean get() = matches && !saving
}

@HiltViewModel
class BackupViewModel @Inject constructor(private val backups: BackupStore, clock: Clock) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Имя, которое предлагается в окне сохранения. */
    val fileName = "budget-backup-${clock.today()}.bpbackup"

    fun onPassword(value: String) = _state.update { it.copy(password = value) }

    fun onRepeat(value: String) = _state.update { it.copy(repeat = value) }

    /** Сохранение в файл, выбранный в системном окне ([target] — его адрес); повторное нажатие во время работы ничего не делает. */
    fun save(target: String) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }
        launchSave(onFailure = { _state.update { it.copy(saving = false) } }) {
            backups.create(target, current.password)
            Notices.show(R.string.backup_saved)
            // Пароль больше не нужен: поля очищаются.
            _state.value = BackupUiState()
        }
    }
}

/** Почему копию нельзя восстановить. */
enum class RestoreError { WRONG_PASSWORD, NOT_BACKUP, TOO_NEW }

data class RestoreUiState(
    val source: String? = null,
    val file: UserFileInfo? = null,
    val password: String = "",
    val checking: Boolean = false,
    val error: RestoreError? = null,
    /** Копия проверена — показать подтверждение замены. */
    val confirm: BackupCheck.Valid? = null,
) {
    val canRestore: Boolean get() = source != null && password.isNotEmpty() && !checking
}

@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val backups: BackupStore,
    private val files: UserFiles,
    private val restarter: AppRestarter,
    clock: Clock,
) : ViewModel() {
    /** Пояс для даты создания копии в подтверждении. */
    val zone: java.time.ZoneId = clock.zone

    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    /** Файл выбран в системном окне. */
    fun onFile(source: String) {
        _state.update { it.copy(source = source, file = null, error = null) }
        viewModelScope.launch {
            val info = runLogged(TAG, "Имя файла не прочиталось") { files.describe(source) }
            _state.update { if (it.source == source) it.copy(file = info) else it }
        }
    }

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Проверка пароля и файла; данные телефона не меняются до подтверждения. */
    fun check() {
        val current = _state.value
        val source = current.source ?: return
        if (!current.canRestore) return
        _state.update { it.copy(checking = true, error = null) }
        launchSave(onFailure = { _state.update { it.copy(checking = false) } }) {
            val result = backups.check(source, current.password)
            _state.update {
                when (result) {
                    is BackupCheck.Valid -> it.copy(checking = false, confirm = result, password = "")
                    BackupCheck.WrongPassword -> it.copy(checking = false, error = RestoreError.WRONG_PASSWORD)
                    BackupCheck.NotBackup -> it.copy(checking = false, error = RestoreError.NOT_BACKUP)
                    BackupCheck.TooNew -> it.copy(checking = false, error = RestoreError.TOO_NEW)
                }
            }
        }
    }

    /** «Отмена» в подтверждении: подготовленная копия удаляется, данные не меняются. */
    fun cancelConfirm() {
        _state.update { it.copy(confirm = null) }
        launchSave { backups.discard() }
    }

    /** «Заменить и перезапустить». */
    fun confirm() {
        if (_state.value.confirm == null) return
        launchSave(onFailure = { _state.update { it.copy(confirm = null) } }) {
            if (backups.scheduleRestore()) {
                restarter.restart()
            } else {
                // Проверенная копия пропала (например, приложение перезапускалось): проверить заново.
                _state.update { it.copy(confirm = null, error = RestoreError.WRONG_PASSWORD) }
            }
        }
    }
}

/** «Удалить все данные»: отметка, удаление файла образцов и перезапуск в онбординг (spec data-export «Удаление всех данных»). */
@HiltViewModel
class DeleteAllViewModel @Inject constructor(
    private val wipe: DataWipe,
    private val devFiles: DevFiles,
    private val restarter: AppRestarter,
) : ViewModel() {
    private var started = false

    fun confirm() {
        if (started) return
        started = true
        launchSave(onFailure = { started = false }) {
            runLogged(TAG, "Удаление образцов не удалось") { devFiles.deleteAll() }
            wipe.schedule()
            restarter.restart()
        }
    }
}

private const val TAG = "Backup"
