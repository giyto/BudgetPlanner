package ru.hznik.budget.ui.lock

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.ui.theme.BudgetTheme

const val LOCK_SCREEN_TAG = "lock_screen"
const val KEY_LOST_TAG = "key_lost"
const val DATABASE_ERROR_TAG = "database_error"

/**
 * Корень интерфейса (design.md add-data-protection, решения 2, 8, 9): пока база не проверена — фон окна,
 * при потере ключа — экран «Не удалось открыть данные», при блокировке — экран блокировки вместо приложения.
 * Приложение уходит из композиции, а его сохраняемое состояние (стек навигации, прокрутка) остаётся в держателе.
 */
@Composable
fun AppRoot(content: @Composable () -> Unit, viewModel: AppRootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    val holder = rememberSaveableStateHolder()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    when (val current = state) {
        RootState.Loading -> Box(Modifier.fillMaxSize().background(colors.background))
        RootState.KeyLost -> KeyLostScreen(onWipe = viewModel::wipe)
        RootState.DatabaseError -> DatabaseErrorScreen(onRetry = viewModel::retry)
        is RootState.Ready -> {
            SecureWindow(current.hideInRecents)
            if (current.locked) LockScreen() else holder.SaveableStateProvider(APP_KEY) { content() }
        }
    }
}

private const val APP_KEY = "app"

/** FLAG_SECURE: содержимое не видно в недавних приложениях, скриншоты запрещены (решение 9). */
@Composable
private fun SecureWindow(enabled: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, enabled) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
}

@Composable
fun LockScreen() {
    val colors = BudgetTheme.colors
    val activity = LocalActivity.current as? FragmentActivity
    val authenticator = LocalDeviceAuthenticator.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var inProgress by remember { mutableStateOf(false) }
    // Проверка открывается сама, когда экран появился и когда пользователь вернулся в приложение;
    // после «Отмены» остаётся кнопка, а окно не открывается снова само.
    var promptOnResume by remember { mutableStateOf(true) }
    val title = stringResource(R.string.auth_title)
    val subtitle = stringResource(R.string.auth_subtitle)
    val unlock: () -> Unit = {
        if (activity != null && !inProgress) {
            inProgress = true
            scope.launch {
                try {
                    val result = authenticator.authenticate(activity, title, subtitle)
                    error = (result as? AuthResult.Error)?.message
                    if (result == AuthResult.Interrupted) promptOnResume = true
                } finally {
                    inProgress = false
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!inProgress) promptOnResume = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (promptOnResume) {
            promptOnResume = false
            unlock()
        }
    }
    // «Назад» сворачивает приложение, а не открывает содержимое.
    BackHandler { activity?.moveTaskToBack(true) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.hero)
            .safeDrawingPadding()
            .testTag(LOCK_SCREEN_TAG),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 28.dp),
        ) {
            LockRing(track = if (colors.isDark) Color(0xFF3A4048) else Color(0xFF2E3338), progress = colors.heroProgress, ink = colors.heroText)
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.heroText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp).semantics { heading() },
            )
            Text(
                error ?: stringResource(R.string.lock_text),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = if (error != null) colors.warnOnHero else colors.heroMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Button(
            onClick = unlock,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_fingerprint), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.lock_unlock), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Кольцо из иконки приложения: дорожка, мятная дуга 70 % и знак рубля. */
@Composable
private fun LockRing(track: Color, progress: Color, ink: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
        Canvas(Modifier.size(132.dp)) {
            val stroke = 13.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(progress, -90f, 252f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("₽", fontSize = 44.sp, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

@Composable
fun KeyLostScreen(onWipe: () -> Unit) {
    val colors = BudgetTheme.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().testTag(KEY_LOST_TAG),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(72.dp))
            Badge(painterResource(R.drawable.ic_lock_alert), container = colors.warnSoft, content = colors.warn)
            Text(
                stringResource(R.string.key_lost_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.key_lost_text),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = colors.sub,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
            )
            PointsCard(
                listOf(
                    R.drawable.ic_database to stringResource(R.string.key_lost_point_kept),
                    R.drawable.ic_refresh to stringResource(R.string.key_lost_point_restart),
                ),
                tileContainer = colors.card2,
                tileContent = colors.muted,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        Button(
            onClick = { confirm = true },
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.warn, contentColor = if (colors.isDark) colors.background else Color.White),
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.key_lost_wipe), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.key_lost_confirm_title)) },
            text = { Text(stringResource(R.string.key_lost_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onWipe() }) { Text(stringResource(R.string.key_lost_confirm), color = colors.warn) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Временный сбой при открытии базы: данные, скорее всего, целы, поэтому удалять их здесь не предлагается. */
@Composable
fun DatabaseErrorScreen(onRetry: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().testTag(DATABASE_ERROR_TAG),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(72.dp))
            Badge(painterResource(R.drawable.ic_refresh), container = colors.card2, content = colors.muted)
            Text(
                stringResource(R.string.key_lost_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.db_error_text),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = colors.sub,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
            )
        }
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.db_error_retry), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Круглая подложка с иконкой, как на шагах онбординга. */
@Composable
fun Badge(icon: Painter, container: Color, content: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp).background(BudgetTheme.colors.card, CircleShape)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).background(container, RoundedCornerShape(20.dp))) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(34.dp))
        }
    }
}

/** Карточка с пунктами: иконка в плитке и текст, строки не ниже 48 dp. */
@Composable
fun PointsCard(points: List<Pair<Int, String>>, tileContainer: Color, tileContent: Color, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        points.forEachIndexed { index, (icon, text) ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 52.dp).padding(vertical = 8.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).background(tileContainer, RoundedCornerShape(10.dp))) {
                    Icon(painterResource(icon), contentDescription = null, tint = tileContent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(text, fontSize = 14.sp, lineHeight = 19.sp, color = colors.ink)
            }
        }
    }
}
