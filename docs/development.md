# Сборка и разработка Bot4VPS Mobile

Bot4VPS Mobile — нативное Android-приложение, написанное на Kotlin с использованием Jetpack Compose.

Проект собирается независимо от серверной части Bot4VPS. Для разработки не требуется устанавливать или запускать сервер: необходимые контракты API включены в исходники приложения в `app/src/main/assets/`.

## Требования

Для сборки приложения необходимы:

| Компонент | Версия |
|---|---|
| JDK | 17 |
| Android SDK Platform | 35 |
| Android SDK Build Tools | 35.0.0 |
| Gradle | 8.14.7 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin | 2.2.20 |
| Hilt | 2.57.1 |
| OkHttp, Logging Interceptor, MockWebServer, OkHttp TLS | 4.12.1 |

Минимальная поддерживаемая версия Android — 8.0 (API 26).

Версии Gradle и зависимостей зафиксированы в конфигурации проекта. Gradle Wrapper проверяет SHA-256 загружаемого дистрибутива.

Gradle 8.14.7 содержит актуальные исправления безопасности ветки 8.14.
Kotlin 2.2.20 используется вместе с Android Gradle Plugin 8.10.1 и Hilt 2.57.1.
Модули OkHttp обновлены согласованно до 4.12.1, которая блокирует восемь
скомпрометированных промежуточных сертификатов eMudhra.

[Gradle 8.14.7](https://docs.gradle.org/8.14.7/release-notes.html) ·
[Совместимость Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html) ·
[Изменения OkHttp 4.12.1](https://github.com/square/okhttp/compare/parent-4.12.0...parent-4.12.1).

Для настройки окружения установите Android SDK через Android Studio:

- **SDK Manager → SDK Platforms → Android 15 (API 35)**.
- **SDK Tools → Show Package Details → Android SDK Build-Tools 35.0.0**.

В настройках Gradle выберите JDK 17.

При первом запуске Gradle требуется интернет для загрузки дистрибутива Gradle и зависимостей.

## Сборка приложения

### Debug APK

Откройте корень проекта в Android Studio, дождитесь синхронизации Gradle и выберите **Build → Build APK(s)**.

Для сборки из командной строки задайте пути к JDK и Android SDK:

```bash
export JAVA_HOME="/путь/к/jdk-17"
export ANDROID_HOME="/путь/к/android-sdk"

chmod +x gradlew
./gradlew --no-daemon :app:assembleDebug
```

В Windows:

```bat
gradlew.bat --no-daemon :app:assembleDebug
```

Готовый APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug-сборка подписывается автоматически локальным debug-ключом. Release-ключ автора для неё не требуется.

### Автоматическая подготовка окружения Linux

Для Linux x86_64 предусмотрен вспомогательный скрипт `tools/android_check.sh`.

Требования: Python 3.12+, curl и nice.

```bash
export BOT4VPS_BUILD_ENV="$HOME/.cache/bot4vps-mobile-build"
bash tools/android_check.sh :app:assembleDebug
```

Скрипт загружает официальные JDK, Gradle и Android SDK, проверяет контрольные суммы и записывает принятие лицензий Android SDK.

Перед использованием ознакомьтесь с лицензиями Android SDK.

Если `BOT4VPS_BUILD_ENV` не задан, инструменты размещаются в `.build-env/`, исключённом из Git.

Помощник не создаёт release-ключи и не устанавливает приложение.

## Release-сборка и подпись

Release-сборка выполняется с использованием R8 и сокращения ресурсов.

Без параметров подписи создаётся неподписанный APK:

```bash
./gradlew --no-daemon :app:assembleRelease
```

Результат:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Для установки на Android APK необходимо подписать. Официальный подписанный APK находится в каталоге `apk/`.

### Собственная подпись

Для выпуска собственной сборки создайте отдельный ключ подписи вне каталога проекта.

После установки JDK 17 выполните:

```bash
python3 tools/prepare_signing.py --create-release \
  --directory "$HOME/.local/share/bot4vps-mobile-signing" \
  --keytool "$JAVA_HOME/bin/keytool"
```

Укажите путь к созданному файлу параметров:

```bash
export BOT4VPS_SIGNING_PROPERTIES="$HOME/.local/share/bot4vps-mobile-signing/release.properties"
```

Затем соберите приложение:

```bash
./gradlew --no-daemon :app:assembleRelease
```

Помощник создаёт RSA-3072 ключ и случайный пароль. Каталог получает права `0700`, ключ и параметры — `0600`. Существующие файлы не заменяются.

Для сохранения прежнего собственного debug-ключа можно явно указать:

```bash
--legacy-debug-key /полный/путь/debug.keystore
```

Оригинальный файл при этом не изменяется.

Вместо файла параметров поддерживаются переменные окружения:

- `BOT4VPS_SIGNING_STORE`;
- `BOT4VPS_SIGNING_STORE_PASSWORD`;
- `BOT4VPS_SIGNING_KEY_ALIAS`;
- `BOT4VPS_SIGNING_KEY_PASSWORD`.

В файле параметров используются поля `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Путь к хранилищу должен быть абсолютным.

Неполные параметры подписи приводят к ошибке сборки.

**Важно:** закрытый ключ автора не входит в репозиторий. Сохраните его резервную копию и используйте тот же ключ для последующих выпусков собственной сборки.

APK с другой подписью нельзя установить поверх официального приложения. Потребуется удалить прежнее приложение и повторно привязать телефон. Данные и настройки серверов на стороне Bot4VPS сохраняются.

При публикации собственной сборки используйте собственное название и соблюдайте [лицензию](../LICENSE) и [правила использования бренда](../NAMING.md).

## Проверка проекта

Для запуска основных автоматических проверок выполните:

```bash
./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease
python3 tools/test_contract.py
```

Контрактная проверка без установленного репозитория сервера использует API-assets, включённые в приложение. Две проверки, требующие исходников сервера, в этом режиме пропускаются.

Для полной сверки с исходниками Bot4VPS:

```bash
BOT4VPS_REPO=/путь/к/Bot4VPS python3 tools/test_contract.py
python3 tools/generate_contract.py --repo /путь/к/Bot4VPS
```

Генератор читает объявления API непосредственно из исходников сервера, не запуская его.

### Тесты инструмента подписи

Для тестирования Python-инструментов потребуются pytest и JDK:

```bash
python3 -m venv /tmp/bot4vps-mobile-tests

/tmp/bot4vps-mobile-tests/bin/pip install pytest

BOT4VPS_TEST_KEYTOOL="$JAVA_HOME/bin/keytool" \
  /tmp/bot4vps-mobile-tests/bin/python -m pytest tests/python
```

Перед выпуском приложения дополнительно проверьте его на физическом устройстве:

- установку и обновление APK;
- подключение и привязку телефона;
- PIN и биометрическую разблокировку;
- работу SSH-терминала;
- получение уведомлений.

Автоматические тесты не заменяют проверку поведения на реальном Android-устройстве.

## Структура проекта

| Каталог | Содержимое |
|---|---|
| `app/src/main/` | Kotlin-код, ресурсы, Manifest и контракты API |
| `app/src/test/` | Android unit-тесты |
| `tests/python/` | Тесты инструментов подписи |
| `gradle/wrapper/` | Gradle Wrapper |
| `tools/` | Подготовка окружения, подпись, генераторы и проверки |
| `apk/` | Официальный APK и метаданные релиза |
| `docs/` | Документация проекта |

Векторный исходник иконки приложения находится в `tools/artwork/launcher.svg`. Для генерации ресурсов используется:

```bash
python3 tools/launcher_icon.py
```

Ключи подписи, Android SDK, кэши и локальные файлы исключены из Git через `.gitignore`.

## Обновления и публикация

При включённой автоматической проверке приложение ищет обновления при открытии, но не чаще одного раза в сутки после успешной проверки. Сетевая ошибка не запускает суточный интервал. Ручная проверка доступна независимо от этого интервала.

Источник обновлений задаётся в:

```text
app/src/main/java/com/bot4vps/mobile/data/model/MobileRelease.kt
```

За него отвечает `MobileReleaseSource`. Проверка источника предусмотрена в `MobileReleaseTest.kt`.

При проверке обновлений учётные данные Web-панели не передаются.

Перед установкой загруженного APK проверяются:

- размер файла;
- SHA-256;
- имя пакета;
- версия;
- сертификат подписи.

Окончательное подтверждение установки выполняет Android.

### Публикация новой версии

Для выпуска обновления:

1. Увеличьте `versionCode` в `app/build.gradle.kts`. Для новой версии измените также `versionName`; повторный выпуск той же версии допускается только с большим `versionCode`.
2. Соберите Release APK с прежним ключом автора.
3. Выполните автоматические проверки и проверьте установку на телефоне.
4. Обновите APK, метаданные релиза и документацию: `README.md`, текущий выпуск в этом руководстве, `CHANGELOG.md` и затронутые сценарии или сведения о безопасности.
5. Выполните проверку согласованности выпуска, приведённую ниже.
6. Опубликуйте проверенные APK, метаданные, исходники и документацию вместе в согласованном репозитории.

В `apk/release.json` необходимо обновить:

- `version`;
- `versionCode`;
- `filename`;
- `size`;
- `sha256`;
- `changelog`;
- при необходимости `installation_note`.

APK и метаданные должны публиковаться вместе.

### Проверка согласованности выпуска

Перед публикацией выполните из корня проекта:

```bash
python3 tools/check_release.py \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --java "$JAVA_HOME/bin/java"
```

Проверка не пересобирает приложение и не обращается к сети. Она сверяет `versionName` и `versionCode` в Gradle, `apk/release.json`, манифесте APK, `README.md` и текущем выпуске этого руководства; проверяет размер, SHA-256, release-подпись, отсутствие debug-флага, текст текущего changelog и локальные ссылки Markdown-документации. Любое расхождение завершает команду с ошибкой.

Официальный сертификат подписи имеет SHA-256:

```text
0e7201279b7e2d47cf12b40feb31423c4eb23000ef270833fa8c758e7c56dbd0
```

При проверке собственной сборки задайте отпечаток своего сертификата через `--certificate-sha256`. Это не меняет подпись APK и не позволяет установить его поверх приложения с другим сертификатом.

### Источник обновлений приложения

Приложение и сервис Bot4VPS читают метаданные из отдельного репозитория приложения:

```text
https://api.github.com/repos/crashdmd/Bot4VPS-Mobile/contents/apk/release.json?ref=main
```

Запросы манифеста и APK используют `Accept: application/vnd.github.raw+json`:
GitHub Contents API возвращает исходные байты файлов, отдельный raw-хост не требуется.

Для нового выпуска увеличьте `versionCode`, соберите APK с прежним release-ключом
автора и опубликуйте APK вместе с `apk/release.json` и исходниками в ветке `main`
репозитория `crashdmd/Bot4VPS-Mobile`. Сервер Bot4VPS самостоятельно сохраняет
проверенную копию в `data/mobile-release/` для Web и Telegram.

Смена источника применяется после установки сборки 1.5.0 с `versionCode=28`
или новее. Текущий выпуск 1.6.0 имеет `versionCode=31` и сохраняет этот источник.
Для последующего изменения адреса обновите `MobileReleaseSource` и его тест,
пересоберите и установите APK: уже установленное приложение сохраняет свой источник.
