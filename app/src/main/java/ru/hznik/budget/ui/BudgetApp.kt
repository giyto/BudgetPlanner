package ru.hznik.budget.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import ru.hznik.budget.R
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import ru.hznik.budget.ui.assistant.AssistantScreen
import ru.hznik.budget.ui.home.HomeRoute
import ru.hznik.budget.ui.onboarding.OnboardingRoute
import ru.hznik.budget.ui.period.NewPeriodRoute
import ru.hznik.budget.ui.period.PaymentEditorRoute
import ru.hznik.budget.ui.period.PeriodRoute
import ru.hznik.budget.ui.categories.CategoriesRoute
import ru.hznik.budget.ui.categories.CategoryEditorRoute
import ru.hznik.budget.ui.navigation.Assistant
import ru.hznik.budget.ui.navigation.AssistantGraph
import ru.hznik.budget.ui.navigation.Categories
import ru.hznik.budget.ui.navigation.CategoryEditor
import ru.hznik.budget.ui.navigation.Home
import ru.hznik.budget.ui.navigation.HomeGraph
import ru.hznik.budget.ui.navigation.Operations
import ru.hznik.budget.ui.navigation.NewPeriod
import ru.hznik.budget.ui.navigation.Onboarding
import ru.hznik.budget.ui.navigation.OperationEditor
import ru.hznik.budget.ui.navigation.Period
import ru.hznik.budget.ui.navigation.PlannedPaymentEditor
import ru.hznik.budget.ui.navigation.OperationsGraph
import ru.hznik.budget.ui.navigation.Settings
import ru.hznik.budget.ui.navigation.Stats
import ru.hznik.budget.ui.navigation.StatsGraph
import ru.hznik.budget.ui.navigation.TopLevelDestination
import ru.hznik.budget.ui.operations.OperationEditorRoute
import ru.hznik.budget.ui.operations.OperationsRoute
import ru.hznik.budget.ui.settings.SettingsScreen
import ru.hznik.budget.ui.stats.StatsScreen
import ru.hznik.budget.ui.theme.BudgetTheme

const val BOTTOM_BAR_TAG = "bottom_bar"

@Composable
fun BudgetApp(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UndoDeletionSnackbar(appViewModel, snackbarHostState)

    // Пока неизвестно, пройден ли онбординг, виден только фон окна: вернувшийся пользователь не увидит мигания онбординга.
    val onboardingDone by appViewModel.onboardingDone.collectAsStateWithLifecycle()
    val done = onboardingDone ?: run {
        Box(Modifier.fillMaxSize().background(BudgetTheme.colors.background))
        return
    }
    // Стартовый экран выбирается один раз: дальше переходы делает сама навигация.
    val startDestination: Any = remember { if (done) HomeGraph else Onboarding }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // Нижняя панель видна только внутри разделов, на «Новой операции» и «Настройках» её нет.
    val resolvedTab = TopLevelDestination.entries.firstOrNull { currentDestination.isIn(it) }
    // В первом кадре NavHost ещё не выбрал экран. Без запомненной вкладки панель появлялась бы
    // кадром позже и сдвигала содержимое, поэтому до этого момента показываем последнее известное состояние.
    var lastKnownTab by rememberSaveable { mutableStateOf(if (startDestination == Onboarding) null else TopLevelDestination.HOME) }
    if (currentDestination != null) {
        SideEffect { lastKnownTab = resolvedTab }
    }
    val currentTab = if (currentDestination == null) lastKnownTab else resolvedTab

    Scaffold(
        containerColor = BudgetTheme.colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentTab != null) {
                BudgetBottomBar(
                    currentTab = currentTab,
                    onTabClick = { navController.navigateToTab(it, reselected = it == currentTab) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // dropUnlessResumed: уходящий экран остаётся нажимаемым во время анимации перехода,
            // без защиты двойное нажатие открывало бы экран дважды или снимало со стека лишнее.
            navigation<HomeGraph>(startDestination = Home) {
                composable<Home> {
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    HomeRoute(
                        onAddOperation = dropUnlessResumed { navController.navigate(OperationEditor()) },
                        onOpenSettings = dropUnlessResumed { navController.navigate(Settings) },
                        onOpenPeriod = dropUnlessResumed { navController.navigate(Period) },
                        onStartNewPeriod = dropUnlessResumed { navController.navigate(NewPeriod) },
                        onOpenOperation = { id ->
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(OperationEditor(id))
                        },
                        // «Все» открывает раздел так же, как его вкладка.
                        onAllOperations = dropUnlessResumed { navController.navigateToTab(TopLevelDestination.OPERATIONS, reselected = false) },
                    )
                }
            }
            navigation<OperationsGraph>(startDestination = Operations) {
                composable<Operations> {
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    OperationsRoute(
                        onAddOperation = dropUnlessResumed { navController.navigate(OperationEditor()) },
                        onOpenOperation = { id ->
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(OperationEditor(id))
                        },
                    )
                }
            }
            navigation<StatsGraph>(startDestination = Stats) {
                composable<Stats> { StatsScreen() }
            }
            navigation<AssistantGraph>(startDestination = Assistant) {
                composable<Assistant> { AssistantScreen() }
            }
            composable<Onboarding> {
                val activity = LocalActivity.current
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(HomeGraph) { popUpTo<Onboarding> { inclusive = true } }
                    },
                    onExit = { activity?.finish() },
                )
            }
            composable<Period> {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                PeriodRoute(
                    onBack = dropUnlessResumed { navController.popBackStack() },
                    onStartNewPeriod = dropUnlessResumed { navController.navigate(NewPeriod) },
                    onAddPayment = dropUnlessResumed { navController.navigate(PlannedPaymentEditor()) },
                    onEditPayment = { id ->
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navController.navigate(PlannedPaymentEditor(id))
                    },
                )
            }
            composable<NewPeriod> { NewPeriodRoute(onClose = dropUnlessResumed { navController.popBackStack() }) }
            composable<PlannedPaymentEditor> { PaymentEditorRoute(onClose = dropUnlessResumed { navController.popBackStack() }) }
            composable<OperationEditor> { OperationEditorRoute(onClose = dropUnlessResumed { navController.popBackStack() }) }
            composable<Settings> {
                SettingsScreen(
                    onBack = dropUnlessResumed { navController.popBackStack() },
                    onOpenCategories = dropUnlessResumed { navController.navigate(Categories) },
                    onOpenPeriod = dropUnlessResumed { navController.navigate(Period) },
                )
            }
            composable<Categories> {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val resumed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                CategoriesRoute(
                    onBack = dropUnlessResumed { navController.popBackStack() },
                    onAdd = { kind -> if (resumed()) navController.navigate(CategoryEditor(kind.name)) },
                    onEdit = { category -> if (resumed()) navController.navigate(CategoryEditor(category.kind.name, category.id)) },
                )
            }
            composable<CategoryEditor> { CategoryEditorRoute(onClose = dropUnlessResumed { navController.popBackStack() }) }
        }
    }
}

/**
 * «Операция удалена» с кнопкой «Отменить», пока AppViewModel держит удаление (5 секунд).
 * Стандартная длительность Short — 4 секунды, поэтому сообщение показывается без срока, а убирает его
 * смена состояния: когда срок истёк, эффект перезапускается и прежний показ отменяется.
 */
@Composable
private fun UndoDeletionSnackbar(viewModel: AppViewModel, hostState: SnackbarHostState) {
    val message = stringResource(R.string.operation_deleted)
    val action = stringResource(R.string.action_undo)
    val pending by viewModel.pendingUndo.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val result = hostState.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismiss()
    }
}

@Composable
private fun BudgetBottomBar(
    currentTab: TopLevelDestination,
    onTabClick: (TopLevelDestination) -> Unit,
) {
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.testTag(BOTTOM_BAR_TAG)) {
        HorizontalDivider(color = colors.line)
        NavigationBar(containerColor = colors.navBar) {
            TopLevelDestination.entries.forEach { tab ->
                NavigationBarItem(
                    selected = tab == currentTab,
                    onClick = { onTabClick(tab) },
                    // Подпись вкладки озвучивается TalkBack, поэтому у иконки описания нет.
                    icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primaryText,
                        selectedTextColor = colors.ink,
                        indicatorColor = colors.primarySoft,
                        unselectedIconColor = colors.muted,
                        unselectedTextColor = colors.muted,
                    ),
                )
            }
        }
    }
}

private fun NavDestination?.isIn(tab: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.graph::class) } == true

private fun NavHostController.navigateToTab(tab: TopLevelDestination, reselected: Boolean) {
    if (reselected) {
        // Повторное нажатие возвращает раздел к его начальному экрану.
        popBackStack(tab.startRoute, inclusive = false)
        return
    }
    navigate(tab.graph) {
        // Стек главной остаётся внизу: «Назад» из любого раздела ведёт на главную.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
