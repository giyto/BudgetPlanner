# Фейковый банк

Инструмент разработки для этапа `add-balance-reconciliation`. Публикует уведомления от имени пакета банка, чтобы пройти автоимпорт и сверку с банком на эмуляторе без настоящих операций. В сборку BudgetPlanner не входит.

## Только эмулятор

- Ставьте только на эмулятор и всегда указывайте его явно: `ANDROID_SERIAL=emulator-5554`.
- На телефон не ставьте. Если на телефоне есть настоящее приложение банка, установка не пройдёт: подписи не совпадают, и Android не даст заменить приложение. Если настоящего банка нет, фейковый встанет под его именем и будет выдавать себя за банк.
- Тексты уведомлений вымышленные. Не вставляйте сюда настоящие уведомления с номерами карт и именами.

## Варианты

| Вариант | Пакет | Задача Gradle |
|---|---|---|
| Сбер | `ru.sberbankmobile` | `:tools:fakebank:installSberDebug` |
| Т-Банк | `com.idamob.tinkoff.android` | `:tools:fakebank:installTbankDebug` |
| Альфа | `ru.alfabank.mobile.android` | `:tools:fakebank:installAlfaDebug` |

## Установка и запуск

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :tools:fakebank:installAlfaDebug
```

```bash
adb -s emulator-5554 shell pm grant ru.alfabank.mobile.android android.permission.POST_NOTIFICATIONS
```

В приложении выберите готовый текст или введите свой и нажмите «Отправить уведомление». Для автоматических проверок текст можно передать при запуске:

```bash
adb -s emulator-5554 shell am start -n ru.alfabank.mobile.android/ru.hznik.fakebank.MainActivity --es text "Покупка в OZON, карта *1234. На сумму 450,00 RUR. Доступно 4 410,00 RUR"
```

BudgetPlanner должен иметь доступ к уведомлениям (шаг онбординга «Автоимпорт» или раздел настроек «Автоимпорт»).

## Удаление

```bash
adb -s emulator-5554 uninstall ru.alfabank.mobile.android
```
