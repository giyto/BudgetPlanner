package ru.hznik.budget.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.NotificationSample
import ru.hznik.budget.domain.notifications.SampleSettings
import ru.hznik.budget.domain.notifications.SupportedBanks
import ru.hznik.budget.notifications.NotificationIntake
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class NotificationSamplesViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val settings: SampleSettings,
    private val file: SamplesFile,
    private val clock: Clock,
) : ViewModel() {
    private val _message = MutableStateFlow<Int?>(null)

    /** Итог последнего действия с файлом или буфером обмена. */
    val message: StateFlow<Int?> = _message.asStateFlow()

    val enabled: StateFlow<Boolean> = settings.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Образцы не старше суток: старые не видны, даже если их ещё не удалили. */
    val samples: StateFlow<List<NotificationSample>> = flowOf(Unit)
        .flatMapLatest { repository.observeSamples(clock.instant().minus(NotificationIntake.SAMPLE_LIFETIME)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.deleteSamplesBefore(clock.instant().minus(NotificationIntake.SAMPLE_LIFETIME)) }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEnabled(enabled) }

    /** Удаляет образцы из базы и файл в «Загрузках». */
    fun deleteAll() = viewModelScope.launch {
        repository.deleteAllSamples()
        runCatching { file.delete() }
        _message.value = R.string.samples_deleted
    }

    fun copy(samples: List<NotificationSample>) {
        file.copyToClipboard(exportText(samples))
        _message.value = R.string.samples_copied
    }

    fun saveToFile(samples: List<NotificationSample>) = viewModelScope.launch {
        _message.value = if (runCatching { file.write(exportText(samples)) }.isSuccess) R.string.samples_saved else R.string.samples_save_failed
    }

    /** Банк и время образца: «Сбер 14.10 12:40». */
    fun label(sample: NotificationSample): String {
        val bank = SupportedBanks.all.firstOrNull { it.id == sample.bankId }?.name ?: sample.bankId
        return "$bank ${TIME.format(sample.postedAt.atZone(clock.zone))}"
    }

    /** Текст для буфера обмена: по строке на образец, старые сверху. */
    fun exportText(samples: List<NotificationSample>): String =
        samples.sortedBy { it.postedAt }.joinToString("\n") { "[${label(it)}] ${it.text}" }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    }
}

/** Пункты режима образцов в разделе «Для разработки» (только отладочная сборка). */
@Composable
fun NotificationSamplesItems(onOpen: () -> Unit, viewModel: NotificationSamplesViewModel = hiltViewModel()) {
    val colors = BudgetTheme.colors
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = viewModel::setEnabled)
                .padding(vertical = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.samples_toggle), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(stringResource(R.string.samples_toggle_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            }
            Spacer(Modifier.padding(start = 12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onOpen),
        ) {
            Text(stringResource(R.string.samples_open), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.weight(1f))
            Text(samples.size.toString(), fontSize = 15.sp, color = colors.muted)
        }
    }
}

/** Экран «Образцы уведомлений»: замаскированные тексты за последние сутки, копирование и удаление. */
@Composable
fun NotificationSamplesRoute(onBack: () -> Unit, viewModel: NotificationSamplesViewModel = hiltViewModel()) {
    val colors = BudgetTheme.colors
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.samples_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        ) {
            if (samples.isEmpty()) {
                item { Text(stringResource(R.string.samples_empty), fontSize = 15.sp, lineHeight = 22.sp, color = colors.sub, modifier = Modifier.padding(8.dp)) }
            }
            items(samples, key = { it.id }) { sample ->
                Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Text(viewModel.label(sample), fontSize = 12.sp, color = colors.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(sample.text, fontSize = 14.sp, lineHeight = 20.sp, color = colors.ink)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
            message?.let { Text(stringResource(it), fontSize = 13.sp, lineHeight = 18.sp, color = colors.sub) }
            Button(
                onClick = { viewModel.saveToFile(samples) },
                enabled = samples.isNotEmpty(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.samples_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                onClick = { viewModel.copy(samples) },
                enabled = samples.isNotEmpty(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.samples_copy), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink) }
            OutlinedButton(
                onClick = viewModel::deleteAll,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.samples_delete), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink) }
        }
    }
}
