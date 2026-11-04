<p align="center"><img src="docs/assets/banner.svg" alt="MikroTik Firewall Control — MFC" width="100%"></p>

<p align="center">
  <a href="https://github.com/silikonoff/MikroTik-Firewall-Control/actions/workflows/build-apk.yml"><img src="https://github.com/silikonoff/MikroTik-Firewall-Control/actions/workflows/build-apk.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/silikonoff/MikroTik-Firewall-Control/releases"><img src="https://img.shields.io/github/v/release/silikonoff/MikroTik-Firewall-Control?include_prereleases&amp;color=3976DF" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8+">
  <img src="https://img.shields.io/badge/RouterOS-7-4EA8FF" alt="RouterOS 7">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue" alt="MIT License"></a>
</p>

<h1 align="center">MikroTik Firewall Control</h1>
<p align="center"><strong>Администратор настраивает. Оператор нажимает одну кнопку.</strong><br>В Android приложение называется <b>MFC</b> — MikroTik Firewall Control.</p>
<p align="center"><a href="https://github.com/silikonoff/MikroTik-Firewall-Control/releases">Скачать APK</a> · <a href="docs/SETUP.md">Настроить MikroTik</a> · <a href="docs/BUILD.md">Собрать из исходников</a> · <a href="https://github.com/silikonoff/MikroTik-Firewall-Control/issues/new/choose">Сообщить о проблеме</a></p>

MFC — небольшое нативное Android-приложение для управления подготовленными группами firewall-правил MikroTik. Оно помогает дать оператору понятную кнопку остановки трафика, сохранив настройки подключения и восстановление трафика за PIN-кодом администратора.

**English:** Native Android controller for RouterOS 7 firewall groups. Operators enable predefined stop rules; only a PIN-authenticated administrator can disable them. Supports REST and native MikroTik API over Wi-Fi, VPN and USB Ethernet.

## Как это работает

| Оператор | Администратор |
|---|---|
| Видит объект, связь и группы | Настраивает роутеры, протокол и маршрут |
| Нажимает «Остановить трафик» и подтверждает | Входит по PIN и восстанавливает трафик |
| Может включить оставшиеся правила MIXED-группы | Меняет PIN, смотрит диагностику и состав правил |
| Пользуется ярлыком нужной группы | Создаёт ярлыки и выбирает рабочий объект |

MFC читает правила MikroTik, **комментарии которых соответствуют маске `APP_STOP_*`**. Вместо `*` укажите название группы: например, `VPN` или `GUEST`.

**Одинаковое название после `APP_STOP_` объединяет правила в одну группу. Разные названия создают отдельные группы** с собственными карточками и кнопками в приложении.

Например:

| Правило на MikroTik | Комментарий правила | Группа в приложении |
|---|---|---|
| Правило 1 | `APP_STOP_VPN` | **VPN** |
| Правило 2 | `APP_STOP_VPN` | **VPN** |
| Правило 3 | `APP_STOP_GUEST` | **GUEST** |

В этом примере появятся две карточки: **VPN** с двумя правилами и **GUEST** с одним. Кнопка группы управляет всеми входящими в неё правилами.

Регистр букв не важен: `app_stop_vpn` и `APP_STOP_VPN` относятся к одной группе. После `|` можно добавить описание, например `APP_STOP_VPN | Остановка VPN-трафика`; на название группы оно не влияет.

Поддерживаются IPv4 **Filter, NAT, Mangle, Raw**. Перед изменением MFC проверяет состав группы, а после — перечитывает состояние правил с роутера.

## Возможности

- **REST и обычный API.** HTTPS/HTTP и API-SSL/API выбираются явно для каждого профиля.
- **Wi-Fi, VPN, USB/OTG Ethernet.** Интернет не нужен для доступа к локальному роутеру.
- **Явный маршрут.** Прямые Wi-Fi/Ethernet без скрытого перехода на мобильную сеть или VPN. Режим «Системная сеть / VPN» использует выбранную Android сеть и фиксирует её на операцию.
- **Спокойное обновление.** Опрос каждые 15 секунд сохраняет неизменившиеся карточки. При ошибке показывает устаревание данных и запрещает команды.
- **Разделение ролей.** Главный экран и ярлыки только включают правила. Выключение — отдельный экран администратора под PIN.
- **Локальное хранение.** Профили зашифрованы Android Keystore/AES-GCM; PIN хранится как PBKDF2-хеш с солью. Облачный сервис приложению не нужен.

## Быстрый старт

1. Установите APK из [Releases](https://github.com/silikonoff/MikroTik-Firewall-Control/releases). Нужны Android 8.0+ и RouterOS 7.
2. Администратор готовит правила и отдельную учётную запись на MikroTik: [краткая настройка](docs/SETUP.md).
3. Откройте **⋮ → Настройки администратора**. Начальный PIN — **`0000`**; смените его в разделе «Доступ администратора».
4. Добавьте подключение: адрес, логин, пароль, REST/API, порт и способ подключения. Для VPN выберите «Системная сеть / VPN».
5. На основном экране дождитесь связи и карточек. Проверьте тестовую группу, затем независимо сверьте её состояние на MikroTik.

Восстановление: **⋮ → Настройки администратора → PIN → Выключить правила / восстановить трафик → группа**.

## Протоколы

| Режим | Служба RouterOS | Порт по умолчанию | Права пользователя |
|---|---|---:|---|
| REST / HTTPS | `www-ssl` | 443 | `read, write, api, rest-api` |
| REST / HTTP | `www` | 80 | `read, write, api, rest-api` |
| Обычный API | `api` | 8728 | `read, write, api` |
| API-SSL | `api-ssl` | 8729 | `read, write, api` |

Для обычного API службы `www`/`www-ssl` не требуются. HTTP REST доступен начиная с RouterOS 7.9. HTTPS и API-SSL проверяют сертификат и имя/IP; автоматического снижения защиты или переключения протокола нет. **HTTP и API без TLS передают учётные данные открыто** — используйте доверенную изолированную сеть или защищённый туннель.

## Что важно понимать

MFC включает подготовленные правила, а не определяет, какой трафик нужно блокировать. Реальный эффект зависит от action, chain, порядка правил, FastTrack, NAT и conntrack. Надпись «Остановка включена» подтверждает состояние правил, а не измеренный обрыв трафика.

Группа изменяется последовательно: транзакции и автоматического отката нет. При потере связи часть правил может уже измениться. Динамические правила не переключаются; invalid-правила нельзя включать. Подробности: [модель безопасности](SECURITY.md).

В системном режиме следующая операция может пойти через другую активную сеть Android, включая мобильную. Если обязателен VPN, используйте системную блокировку соединений без VPN.

## Статус и сборка

Версия **0.2.11 — предварительная**. База: Kotlin, Android SDK 35 (target 34, min 26), JDK 17+, Gradle 8.9, AGP 8.7.3. В публичном релизе опубликован существующий debug APK; это обозначено в имени файла. CI-артефакты имеют временную debug-подпись и не предназначены для обновления установленного релизного APK. Ключ подписи в репозиторий не включён.

Последняя локальная проверка исходников: **86 JUnit без ошибок**, lint без ошибок. Ранее работа REST/HTTP и переключение тестовых групп подтверждались на смартфоне и роутере. Новые API/API-SSL, Android-маршруты и ограничения ролей версии 0.2.11 требуют аппаратной приёмки: [чек-лист](docs/TESTING.md). Это не заявление о полной аппаратной совместимости.

```powershell
.\build-apk.ps1 -SdkPath 'C:\Android\Sdk' -JdkPath 'C:\Java\jdk-21'
```

Подробнее: [сборка и тесты](docs/BUILD.md), [изменения](CHANGELOG.md), [участие в разработке](CONTRIBUTING.md).

## Лицензия

[MIT](LICENSE) · Автор: [silikonoff](https://github.com/silikonoff). Независимый проект; не является официальным продуктом MikroTik. Название MikroTik принадлежит соответствующему правообладателю.
