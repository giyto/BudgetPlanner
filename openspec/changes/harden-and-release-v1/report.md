# Отчёт этапа harden-and-release-v1

## Меры безопасности (таблица 10.2 и раздел 10.3 ТЗ, задача 8.3)

Пути: `T` - `app/src/test/java/ru/hznik/budget`, `A` - `app/src/androidTest/java/ru/hznik/budget`, `SST` - `T/SecurityStaticChecksTest.kt` (статические проверки манифеста, правил бэкапа, сети, логов и зависимостей).

| Мера | Чем подтверждена |
|---|---|
| Обрабатываются только пакеты банков, остальное отбрасывается без логирования | `A/ui/ImportFlowTest` (настоящий `StatusBarNotification` чужого пакета), `T/notifications/NotificationIntakeTest`, `A/data/local/NotificationDatabaseTest`; отсутствие логов - SST (вывод только постоянного текста) |
| Исходный текст уведомления не сохраняется; образцы только в отладочной сборке, с маскировкой, удаляются через 24 часа | `NotificationIntakeTest` (теперь в `app/src/testDebug`), `SampleMaskerTest`, `NotificationDatabaseTest`; код образцов только в `src/debug` - в release-файле нет его классов и строк (проверка `dexdump`, задача 9.2), `A/ui/DevToolsFlowTest` |
| Коды подтверждения не распознаются и не сохраняются | `SberParserTest`, `TBankParserTest`, `AlfaParserTest` (тест кодов добавлен на этом этапе) |
| База зашифрована SQLCipher, ключ защищён Android Keystore | `A/data/security/DatabaseEncryptionTest` (8 сценариев: ключ, перенос, потеря ключа), `A/data/backup/BackupRestoreTest` (копия не открывается без пароля) |
| База и настройки исключены из облачного бэкапа | SST: `allowBackup=false`, все домены исключены в `data_extraction_rules.xml` и `backup_rules.xml` |
| Вход по биометрии или PIN, блокировка при уходе в фон | `T/domain/lock/AppLockTest`, `A/ui/LockFlowTest` (экран блокировки, таймаут, снятие блокировки экрана телефона - в том числе для настроек из копии, задача 3.5) |
| ИИ выключен по умолчанию, в модель уходят результаты инструментов, есть режим без магазинов | `T/data/settings/AiSettingsStoreTest`, `T/ai/ChatSessionTest`, `T/ai/AssistantEngineTest`, `T/ai/tools/AssistantToolsTest`, `A/ui/AssistantFlowTest`. Кроме результатов инструментов модель получает вопрос пользователя, а подсказка категорий для черновиков - названия магазинов (spec ai-assistant, режим «без магазинов» её отключает) |
| Только HTTPS, российский корневой сертификат - только для доменов GigaChat | SST (`network_security_config.xml`: без открытого трафика, сертификат только для двух хостов), `T/ai/gigachat/GigaChatClientTest` |
| Ключ GigaChat только в `local.properties` вне git | `.gitignore`, `app/build.gradle.kts`; SST запрещает логирование HTTP (`HttpLoggingInterceptor`, плагин логирования Ktor); ключ в APK не скрывается - принято ТЗ |
| В журнале release-сборки нет сумм, магазинов, текстов | SST (только постоянный текст и класс исключения, сканирует `src/main`, `src/debug`, `src/release`); ручная проверка журнала release-версии на эмуляторе: онбординг, трата, помощник, копия и восстановление - ни сумм, ни магазинов, ни текстов, ни ключей, ни пароля копии (задача 8.2) |
| Не подключается к банку, не хранит данные входа | Кода входа в банк нет; единственные токены - GigaChat, только в памяти (`GigaChatAuth`) |
| Не читает SMS | SST (точный набор разрешений исходного манифеста); `aapt dump permissions` release-файла: нет `READ_SMS`, `ACCESS_NETWORK_STATE`, памяти и местоположения, из сетевых только `INTERNET` (задача 8.1) |
| Данные уходят только в модель ИИ и только когда она включена | Единственный HTTP-клиент - `ai/AiModule.kt`; SST (соединения только с GigaChat по HTTPS), `AssistantOffNoRequestsFlowTest` |
| Нет аналитики и рекламы | SST: в `libs.versions.toml` и `app/build.gradle.kts` нет SDK аналитики, рекламы и сбора сбоев (тест добавлен на этом этапе) |

Разрешения release-файла сверх раздела 11 ТЗ - служебные разрешения библиотек без доступа к данным: `USE_FINGERPRINT` (Biometric), `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (WorkManager), `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX).

## Скорость

- Холодный запуск release на эмуляторе (Pixel 8, API 37), 5 запусков: первый кадр 0,84–1,05 с, сумма на главной («Fully drawn») 1,28–1,55 с без компиляции; после компиляции по профилям библиотек (как при установке и в простое телефона) - первый кадр 0,36–0,44 с, сумма 0,59–0,71 с. Замер на телефоне - в задаче 11.3.
- 50 000 операций в зашифрованной базе (`A/data/local/OperationsPerformanceTest`): первая порция списка, обновление после вставки, поиск и расчёт главной - меньше 1 с. Полный список читался 4 с, поэтому экран операций грузит порции по 300 операций; индексы и миграция 7→8 не понадобились.

## Release-сборка

- R8 и сжатие ресурсов: размер 15 МБ вместо 38 МБ, предупреждений R8 нет, своих правил не понадобилось.
- Ручной обход release на эмуляторе без сбоев: онбординг, главная, новая операция, операции, статистика, помощник (ответ GigaChat), настройки, резервная копия, восстановление с перезапуском. Раздела «Для разработки» нет. Виджет: классы приёмников и фоновых задач R8 оставил под исходными именами, живая проверка - на телефоне (11.3).
- Подпись: проверена одноразовым тестовым ключом (`apksigner verify`), ключ удалён; ключ пользователя создаётся по `docs/release-signing.md`.

## Критерии готовности 1.0 (раздел 14 ТЗ, задача 11.4)

| Критерий | Доказательство | Статус |
|---|---|---|
| «Можно сегодня» верно во всех граничных случаях FR-03 | `T/domain/budget/BudgetCalculatorTest` (19 сценариев), `IncomeDatesTest`, `A/ui/HomeFlowTest`, `A/ui/LargeFontTest` | тесты |
| Покупка по карте появляется автоматически | `A/ui/ImportFlowTest` (в том числе скорость до 3 с), `A/ui/AutoImportFlowTest`, `T/domain/usecase/ImportNotificationTest`, парсеры Сбера, Т-Банка, Альфы | тесты; на телефоне с банком - 11.3 |
| Чужие уведомления нигде не сохраняются | `A/ui/ImportFlowTest` (уведомление чужого пакета), `T/notifications/NotificationIntakeTest`, `A/data/local/NotificationDatabaseTest` | тесты |
| База зашифрована, вне облачного бэкапа, вход защищён | `A/data/security/DatabaseEncryptionTest`, SST, `A/ui/LockFlowTest` | тесты |
| ИИ отвечает на вопросы FR-08 через инструменты, не получает базу целиком | `T/ai/AssistantEngineTest`, `T/ai/tools/AssistantToolsTest`, `A/ui/AssistantFlowTest`; ответ GigaChat через инструмент в release на эмуляторе | тесты, ручная проверка |
| Виджет работает и обновляется | `A/reminders/ReminderWorkTest`, `A/ui/RemindersFlowTest`, `T/notifications/CheckNotificationsTest` (обновление виджета после импорта, через `CountingWidgetRefresher`) | тесты; живой виджет - 11.3 |
| Списания и пополнения попадают автоматически, расхождение находится и исправляется (сценарий 5.9) | `T/domain/usecase/ReconciliationTest`, `A/ui/ReconcileFlowTest`, `A/data/local/ReconciliationDatabaseTest` | тесты; на телефоне с банком - 11.3 |
| Все тесты проходят на API 29 и API 37 | полный прогон по классам, 52 класса на каждом (задача 11.1) | тесты |

## Ревью (задача 11.2)

Агент `code-reviewer` проверил все изменения этапа: 1 серьёзное замечание, 4 мелких, все исправлены.

| Замечание | Исправление | Тест |
|---|---|---|
| Прежняя база (`.old`) удалялась до того, как Room открыл копию; сбой миграции или обрыв посреди подмены терял данные | Отметка `budget.db.restoring`, `.old` удаляется только из `RoomDatabase.Callback.onOpen`, при незавершённом восстановлении прежняя база возвращается | `BackupRestoreTest`: `restoreNotConfirmedByRoomRollsBackOnNextStart`, `restoreConfirmedByRoomDeletesPreviousDatabase`, `interruptedSwapBringsPreviousDatabaseBack` |
| Копия со схемой ≤ 0 роняла Room | Отклоняется как «не копия» | `wrongPasswordForeignFileAndNewerBackupChangeNothing` |
| Настройки из копии записывались один раз и могли потеряться или уронить запуск | Запись под общим замком, пока есть файл настроек, ошибка не роняет приложение | - |
| Подгрузка списка могла остановиться на дне с 300+ операциями | Подгрузка следит за числом операций, а не дней | `OperationsViewModelTest`: порции и сброс при смене фильтра |
| При ошибке записи оставался пустой файл копии или CSV; даты экспорта терялись при смерти процесса | Файл удаляется через `DocumentsContract`, даты в `SavedStateHandle` | - |
| Копия без транзакции могла получиться несогласованной во время автоимпорта | `sqlcipher_export` и запись сведений о копии в одной транзакции | `BackupRestoreTest` |

## Тесты (задачи 1.1 и 11.1)

- Unit-тесты: все проходят.
- Instrumented, API 37 (Pixel 8): 52 класса по одному, все проходят.
- Instrumented, API 29 (Pixel 4): 52 класса по одному, все проходят.
