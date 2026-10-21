# Подпись release-версии

Release-версия подписывается вашим ключом. Ключ и пароли хранятся только у вас: файлы `keystore.properties`, `*.jks` и `*.keystore` перечислены в `.gitignore`, в git они не попадают. Без `keystore.properties` release собирается неподписанной.

## 1. Создать ключ (один раз)

Храните ключ вне папки проекта, например в `D:\keys`. Команда спросит пароль и данные владельца, вводите их сами:

```bash
keytool -genkeypair -v -keystore D:/keys/budgetplanner-release.jks -alias budgetplanner -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` входит в JDK Android Studio: `C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe`.

Сделайте копию файла ключа и запишите пароль. Если ключ потерян, обновить установленное приложение будет нельзя: только удалить и поставить заново, данные вернутся из резервной копии.

## 2. Файл `keystore.properties`

Создайте в корне проекта (рядом с `settings.gradle.kts`) файл `keystore.properties`:

```properties
storeFile=D:/keys/budgetplanner-release.jks
storePassword=ваш пароль хранилища
keyAlias=budgetplanner
keyPassword=ваш пароль ключа
```

## 3. Сборка и проверка

```bash
./gradlew :app:assembleRelease
```

Готовый файл: `app/build/outputs/apk/release/app-release.apk`. Проверка подписи:

```bash
D:/Android/Sdk/build-tools/36.0.0/apksigner.bat verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Переход телефона с отладочной версии

Отладочная и release-версии подписаны разными ключами, поэтому release не ставится поверх отладочной:

1. В отладочной версии: Настройки → Данные → Резервная копия.
2. Удалить отладочную версию.
3. Установить release-версию, пройти онбординг и открыть Настройки → Данные → Восстановить из копии.
