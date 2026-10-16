# Макеты BudgetPlanner

- `screenshots/` — снимки всех экранов в PNG (390×844, плотность 2×).
- `mockups/` — копия исходников макетов от 13 октября 2026. Файлы `.dc.html` открываются только в редакторе макетов, в обычном браузере они не отрисуются. Исходники нужны как источник точных цветов, размеров и текстов при вёрстке на Compose.

## Экраны

| Экран | Светлая тема | Тёмная тема |
|---|---|---|
| Главный экран | ![](screenshots/Main.png) | ![](screenshots/DarkMain.png) |
| Главный, расхождение с банком | ![](screenshots/MainMismatch.png) | ![](screenshots/DarkMainMismatch.png) |
| Черновики из уведомлений | ![](screenshots/Drafts.png) | ![](screenshots/DarkDrafts.png) |
| Новая трата | ![](screenshots/Add.png) | ![](screenshots/DarkAdd.png) |
| Операции | ![](screenshots/Operations.png) | ![](screenshots/DarkOperations.png) |
| Статистика | ![](screenshots/Stats.png) | ![](screenshots/DarkStats.png) |
| Статистика: по дням и сравнение | ![](screenshots/StatsDays.png) | ![](screenshots/DarkStatsDays.png) |
| Статистика: раскрытое «Остальное» | ![](screenshots/StatsOther.png) | ![](screenshots/DarkStatsOther.png) |
| Операции категории из статистики | ![](screenshots/CategoryOperations.png) | ![](screenshots/DarkCategoryOperations.png) |
| ИИ-помощник | ![](screenshots/Assistant.png) | ![](screenshots/DarkAssistant.png) |
| Онбординг: автоимпорт | ![](screenshots/Onboarding.png) | ![](screenshots/DarkOnboarding.png) |
| Сверка с банком | ![](screenshots/Reconcile.png) | ![](screenshots/DarkReconcile.png) |
| Онбординг: защита входа | ![](screenshots/OnboardingProtection.png) | ![](screenshots/DarkOnboardingProtection.png) |
| Экран блокировки | ![](screenshots/LockScreen.png) | ![](screenshots/DarkLockScreen.png) |
| Настройки | ![](screenshots/Settings.png) | ![](screenshots/DarkSettings.png) |
| Потеря ключа базы | ![](screenshots/KeyLost.png) | ![](screenshots/DarkKeyLost.png) |
| Онбординг: снять ограничение | ![](screenshots/OnboardingRestricted.png) | ![](screenshots/DarkOnboardingRestricted.png) |
| Онбординг: автозапуск на Xiaomi | ![](screenshots/OnboardingAutostart.png) | ![](screenshots/DarkOnboardingAutostart.png) |
| Главная: новый период после зачисления и «Автоимпорт остановлен» | ![](screenshots/MainImportBanners.png) | ![](screenshots/DarkMainImportBanners.png) |
| Черновики: перевод себе, пара найдена сама | ![](screenshots/DraftsOwnTransfer.png) | ![](screenshots/DarkDraftsOwnTransfer.png) |
| Черновики: «Это перевод себе» и запоминание имени | ![](screenshots/DraftsOwnName.png) | ![](screenshots/DarkDraftsOwnName.png) |
| Операции: перевод себе | ![](screenshots/OperationsOwnTransfer.png) | ![](screenshots/DarkOperationsOwnTransfer.png) |
| Сверка с банком по каждой карте | ![](screenshots/ReconcileCards.png) | ![](screenshots/DarkReconcileCards.png) |
| Главная: расхождение по нескольким картам | ![](screenshots/MainMismatchCards.png) | ![](screenshots/DarkMainMismatchCards.png) |
| Период: «Сверить с банком» и последняя сверка | ![](screenshots/PeriodReconcile.png) | ![](screenshots/DarkPeriodReconcile.png) |
| Ручная сверка: выбор счёта и ввод остатка | ![](screenshots/ReconcileManual.png) | ![](screenshots/DarkReconcileManual.png) |
| Операции: фильтр по карте и корректировка | ![](screenshots/OperationsCardFilter.png) | ![](screenshots/DarkOperationsCardFilter.png) |
| Новая трата по карте из сверки | ![](screenshots/AddForCard.png) | ![](screenshots/DarkAddForCard.png) |
| Черновики: «Знакомый магазин» и «Запомнить» | ![](screenshots/DraftsKnownStore.png) | ![](screenshots/DarkDraftsKnownStore.png) |
| Настройки: правила для магазинов | ![](screenshots/SettingsRules.png) | ![](screenshots/DarkSettingsRules.png) |
| Правило: выбор категории | ![](screenshots/RuleCategorySheet.png) | ![](screenshots/DarkRuleCategorySheet.png) |
| Виджет на рабочем столе: средний, маленький, скрытая сумма | ![](screenshots/Widgets.png) | ![](screenshots/DarkWidgets.png) |
| Уведомления приложения | ![](screenshots/NotificationsShade.png) | ![](screenshots/DarkNotificationsShade.png) |
| Онбординг: подсказки вовремя | ![](screenshots/OnboardingNotifications.png) | ![](screenshots/DarkOnboardingNotifications.png) |

Исходник каждого снимка лежит в `mockups/` под тем же именем с расширением `.dc.html`. Исключение — экраны статистики (`Stats*`, `CategoryOperations`): карточку категорий переделали (список категорий под кольцом, раскрытие «Остального», переход к операциям категории), поэтому старый макет статистики удалён, а снимки сделаны с самого приложения на эмуляторе в том же размере. Запись о статистике в `canvas.json` относится к удалённому макету.

Экраны защиты входа (`OnboardingProtection`, `LockScreen`, `KeyLost`) нарисованы отдельно, в редакторе их нет: исходник всех трёх — обычный HTML `mockups/DataProtection.html`, он открывается в любом браузере. Экран и тема выбираются параметрами, например `DataProtection.html?screen=LockScreen&theme=dark`.

Экраны автоимпорта (`OnboardingRestricted`, `OnboardingAutostart`, `MainImportBanners`, `DraftsOwnTransfer`, `DraftsOwnName`, `OperationsOwnTransfer`) и сверка по картам (`ReconcileCards`) нарисованы так же: исходник — `mockups/NotificationImport.html`, экран и тема выбираются параметрами, например `NotificationImport.html?screen=DraftsOwnTransfer&theme=dark`.

Остальные экраны сверки (`MainMismatchCards`, `PeriodReconcile`, `ReconcileManual`, `OperationsCardFilter`, `AddForCard`) лежат в `mockups/Reconciliation.html` и выбираются так же, например `Reconciliation.html?screen=ReconcileManual&theme=dark`.

Экраны автокатегорий (`DraftsKnownStore`, `SettingsRules`, `RuleCategorySheet`) лежат в `mockups/AutoCategorization.html` и выбираются так же, например `AutoCategorization.html?screen=SettingsRules&theme=dark`.

Тёмные варианты и `MainMismatch` не содержат своей вёрстки: они подключают светлый экран с параметром `theme="dark"` или `state="mismatch"`. Палитра обеих тем описана в объекте `THEMES` в начале скрипта каждого экрана, состояние «перерасход» главного экрана задаётся параметром `state="overspend"` в `Main.dc.html`.

`canvas.json` хранит расположение экранов на холсте редактора.


`Onboarding` (шаг автоимпорта) тоже отрисован из `mockups/NotificationImport.html`: `Onboarding.dc.html` в редакторе обновлён теми же текстами, но открывается только там. Настройки целиком — один высокий экран в `mockups/Settings.html` (`Settings.html?theme=dark`), со всеми разделами по порядку.

Виджет, уведомления и шаг «Подсказки вовремя» (`Widgets`, `NotificationsShade`, `OnboardingNotifications`) лежат в `mockups/WidgetReminders.html` и выбираются так же, например `WidgetReminders.html?screen=NotificationsShade&theme=dark`. Раздел «Уведомления» добавлен в полный экран `Settings`.
