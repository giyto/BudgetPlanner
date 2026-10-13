# Макеты BudgetPlanner

Живая версия макетов (просмотр и правки): https://claude.ai/artifact/GSyVfbyh3Y7XNk3RwBPmtx

В папке `mockups/` лежит копия исходников этих макетов от 13 октября 2026. Файлы `.dc.html` открываются только в редакторе по ссылке выше, в обычном браузере они не отрисуются. Копия нужна как источник точных цветов, размеров и текстов при вёрстке на Compose.

## Экраны

| Экран | Светлая тема | Тёмная тема |
|---|---|---|
| Главный экран | `Main.dc.html` | `DarkMain.dc.html` |
| Главный, расхождение с банком | `MainMismatch.dc.html` | `DarkMainMismatch.dc.html` |
| Черновики из уведомлений | `Drafts.dc.html` | `DarkDrafts.dc.html` |
| Новая трата | `Add.dc.html` | `DarkAdd.dc.html` |
| Операции | `Operations.dc.html` | `DarkOperations.dc.html` |
| Статистика | `Stats.dc.html` | `DarkStats.dc.html` |
| ИИ-помощник | `Assistant.dc.html` | `DarkAssistant.dc.html` |
| Онбординг: автоимпорт | `Onboarding.dc.html` | `DarkOnboarding.dc.html` |
| Сверка с банком | `Reconcile.dc.html` | `DarkReconcile.dc.html` |

Тёмные варианты и `MainMismatch` не содержат своей вёрстки: они подключают светлый экран с параметром `theme="dark"` или `state="mismatch"`. Палитра обеих тем описана в объекте `THEMES` в начале скрипта каждого экрана, состояние «перерасход» главного экрана задаётся параметром `state="overspend"` в `Main.dc.html`.

`canvas.json` хранит расположение экранов на холсте редактора.
