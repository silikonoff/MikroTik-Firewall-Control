# Сборка

Нужны JDK 17+ (проверен JDK 21), Android SDK с `platforms;android-35` и `build-tools;34.0.0`. Gradle 8.9 загружает build-скрипт с проверкой SHA256. При первом запуске нужен интернет для зависимостей.

## Windows / PowerShell

```powershell
.\build-apk.ps1 -SdkPath 'C:\Android\Sdk' -JdkPath 'C:\Java\jdk-21'
```

Скрипт выполняет unit tests, lint и assembleDebug. APK и SHA256 появятся в `release/` и `dist/`.

## Linux / macOS

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
bash build-apk.sh
```

Нужны `curl`, `unzip`, `sha256sum` либо `shasum`. APK появится в `dist/`.

## Android Studio / Gradle

Откройте корневую папку проекта. Укажите SDK через Android Studio или `ANDROID_HOME`. Параметры проекта: AGP 8.7.3, Kotlin 1.9.25, minSDK 26, targetSDK 34, compileSDK 35.

С установленным Gradle 8.9:

```text
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Исходники организованы в `core` (модель, группы, preflight/readback), `net` (REST/API и привязка Android Network), `security` (профили/PIN), Activities (оператор/администратор). Unit tests находятся в `app/src/test` и `tests/shared`.

## Подпись

Локальная сборка использует debug-ключ разработчика. Сохраняйте его для последующих обновлений. CI создаёт временный debug-ключ: APK из разных CI-запусков может быть несовместим по подписи с уже установленным приложением. Для собственной дистрибуции настройте постоянный приватный ключ отдельно; не добавляйте его в Git.

Официальный APK v0.2.11 в Releases сохраняет прежнюю подпись проекта. Обновление названия сохраняет ключ подписи предыдущего релиза. SHA256 опубликован рядом с APK; побайтовая идентичность независимых сборок не обещается.
