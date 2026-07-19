# Statify

Учёт игрового времени на Minecraft-сервере с записью в MySQL/MariaDB и набором плейсхолдеров: имя, UUID, IP, всего / за день / за неделю / за месяц.

Четыре независимых модуля с общей БД и общей схемой:

- `statify-paper` — плагин под **Paper 1.20.4** и **1.21.x**. Интеграция с PlaceholderAPI. Java 21.
- `statify-fabric` — мод под **Fabric 1.21.8** (loader 0.17.2, api 0.136.1). Интеграция с [Text Placeholder API](https://placeholders.pb4.eu/) от pb4. Java 21.
- `statify-forge` — мод под **Forge 1.20.1** (47.4.10). Плейсхолдеров нет (у Forge 1.20.1 нет мода с совместимым API). Java 17.
- `statify-velocity` — плагин под **Velocity 3.3.x**. Игровое время не считает: только «липкий» коннект — возвращает игрока на тот сервер, где он был перед выходом. Использует общую таблицу `statify_players` (расширяется колонками `last_server`). Java 17.

## Сборка

Paper/Fabric требуют JDK 21, Forge и Velocity — JDK 17 (Gradle сам подтянет toolchain, но для host-JVM ставьте нужную версию).

### Инициализация gradle wrapper

Файлы `gradlew`, `gradlew.bat` и папка `gradle/wrapper/` не хранятся в репозитории (см. [.gitignore](.gitignore)) — после свежего клона их нужно сгенерировать. Понадобится установленный локально Gradle (`gradle --version`; если нет — поставьте через [Gradle install](https://gradle.org/install/), sdkman или пакетный менеджер).

```bash
# Paper и Velocity — Gradle 8.10, Fabric — 8.12, Forge — 8.10 (совместим с ForgeGradle 6).
# Версию можно уточнить в build.gradle.kts / gradle-wrapper.properties референсного модуля.

cd statify-paper && gradle wrapper --gradle-version 8.10 && cd ..
cd statify-fabric && gradle wrapper --gradle-version 8.12 && cd ..
cd statify-forge && gradle wrapper --gradle-version 8.10 && cd ..
cd statify-velocity && gradle wrapper --gradle-version 8.10 && cd ..
```

После этого в каждом модуле появятся `gradlew`, `gradlew.bat` и `gradle/wrapper/`. Ниже — команды сборки.

### Сборка модулей

```bash
# Paper
cd statify-paper
./gradlew shadowJar
# → build/libs/statify-paper-1.0.1.jar

# Fabric
cd statify-fabric
./gradlew build
# → build/libs/statify-fabric-1.0.1.jar

# Forge
cd statify-forge
./gradlew build
# → build/libs/statify-forge-1.0.1.jar

# Velocity
cd statify-velocity
./gradlew shadowJar
# → build/libs/statify-velocity-1.0.1.jar
```

## Установка

- Paper: положить jar в [paper/plugins](paper/plugins), сервер стартовать один раз (создастся `plugins/Statify/config.yml`), заполнить БД, перезапустить.
- Fabric: положить jar в [fabric/mods](fabric/mods), сервер стартовать один раз (создастся `config/statify/config.yml`), заполнить БД, перезапустить.
- Forge: положить jar в папку `mods/` форджового сервера, стартовать (создастся `config/statify/config.yml`), заполнить БД, перезапустить.
- Velocity: положить jar в [velocity/plugins](velocity/plugins), прокси стартовать один раз (создастся `plugins/statify/config.yml`), заполнить БД, перезапустить.

По умолчанию `database.type: none` — плагин/мод при этом выводит warn и отключается.

## Конфиг

`config-version` совпадает с версией плагина. При апгрейде плагина старый `config.yml` автоматически переименовывается в `config.old.<старая-версия>.yml`, а на его месте создаётся свежий.

```yaml
config-version: '1.0.1'
server-name: 'lobby'

database:
  type: 'none'          # mysql | mariadb | none
  host: 'localhost'
  port: 3306
  database: 'statify'
  username: 'statify'
  password: 'change_me'
  use-ssl: false
  pool-size: 6

# Периодический flush в БД (минут). Обычно данные пишутся только при выходе
# игрока и при переходе через полночь, но раз в N минут делается страховочный
# снапшот — на случай падения сервера.
flush-interval-minutes: 15

# Форматирование времени для плейсхолдеров вроде %statify_total%.
# Нулевые компоненты пропускаются: 45s -> '45s', 5m 30s -> '5m 30s'.
format:
  suffix-day: 'd'
  suffix-hour: 'h'
  suffix-minute: 'm'
  suffix-second: 's'
  separator: ' '        # разделитель между компонентами
  zero: '0s'            # что вывести при нулевом времени
```

Примеры настройки формата:

```yaml
# Русский:
format: { suffix-day: 'д', suffix-hour: 'ч', suffix-minute: 'м', suffix-second: 'с', separator: ' ', zero: '0с' }
# -> '3д 2ч 15м'

# Слитно с двоеточиями:
format: { suffix-day: ':', suffix-hour: ':', suffix-minute: ':', suffix-second: '', separator: '', zero: '0' }
# -> '3:2:15:0'
```

## Схема БД

Создаётся автоматически:

- `statify_players (uuid, name, last_seen, last_ip, last_server)` — актуальные данные игрока по UUID.
  - `last_ip` пишут Paper / Fabric / Forge / Velocity при подключении игрока (IPv4 или IPv6, до 45 символов).
  - `last_server` заполняет только `statify-velocity` — Paper/Fabric/Forge её игнорируют.
  - Если таблица создана более старой версией — недостающие колонки добавляются через `ALTER TABLE ADD COLUMN`, попытка игнорируется с MySQL/MariaDB error code 1060.
- `statify_daily (server, uuid, day, seconds)` — накопленные секунды по дню и серверу. Используется Paper/Fabric/Forge. Velocity её не трогает.

Суммы за неделю/месяц/всё — агрегаты по `statify_daily`.

## Плейсхолдеры

### Paper (PlaceholderAPI)

| Плейсхолдер | Значение |
| --- | --- |
| `%statify_name%` | имя игрока |
| `%statify_uuid%` | UUID игрока |
| `%statify_ip%` | последний IP игрока из БД (пусто, если ещё не записан) |
| `%statify_server%` | имя локального сервера из конфига |
| `%statify_total%` | всё время на текущем сервере (`Xh Ym Zs`) |
| `%statify_day%` / `_week%` / `_month%` | за сегодня / текущую неделю / текущий месяц |
| `%statify_total_seconds%` / `_day_seconds` / `_week_seconds` / `_month_seconds` | то же в секундах |

Кросс-серверные варианты — тот же плейсхолдер с суффиксом имени сервера:

| Плейсхолдер | Значение |
| --- | --- |
| `%statify_total_<server>%` | всё время игрока на сервере `<server>` |
| `%statify_day_<server>%` | за сегодня на `<server>` |
| `%statify_week_<server>%` / `_month_<server>%` | за неделю / месяц на `<server>` |
| `%statify_total_seconds_<server>%` | всё время в секундах на `<server>` |

Пример: если `server-name: lobby` и в БД есть данные с сервера `survival`, на lobby можно вывести `%statify_total_survival%`. Для локального сервера значение растёт в реальном времени (плюс живая секунда сессии); для чужого сервера — берётся только то, что уже записано в БД.

Топ игроков по общему времени (N от 1 до 20):

| Плейсхолдер | Значение |
| --- | --- |
| `%statify_top_<N>_name%` | имя игрока на N-й позиции (текущий сервер) |
| `%statify_top_<N>_uuid%` | UUID |
| `%statify_top_<N>_time%` | форматированное время (`3d 2h 15m`) |
| `%statify_top_<N>_seconds%` | секунды |
| `%statify_top_<N>_<field>_<server>%` | тот же топ на указанном сервере |

Пример: `%statify_top_1_name%`, `%statify_top_5_time_survival%`. Результат кешируется на 30 секунд.

### Fabric (Text Placeholder API)

Базовые: `%statify:name%`, `%statify:uuid%`, `%statify:ip%`, `%statify:server%`, `%statify:total%`, `%statify:day%`, `%statify:week%`, `%statify:month%`.

`ip` — последний IP игрока из `statify_players.last_ip`. Значение кэшируется на 5 секунд.

Кросс-серверные — через аргумент после `/`:

- `%statify:total/<server>%`
- `%statify:day/<server>%`, `%statify:week/<server>%`, `%statify:month/<server>%`

Пример: `%statify:total/survival%`.

Топ по общему времени (N от 1 до 20):

- `%statify:top_name/<N>%`, `%statify:top_uuid/<N>%`, `%statify:top_time/<N>%`, `%statify:top_seconds/<N>%`
- с указанием сервера: `%statify:top_name/<N>/<server>%` и т.д.

Пример: `%statify:top_name/1%`, `%statify:top_time/5/survival%`. Кэш топа — 30 секунд.

Результаты обычных плейсхолдеров кешируются на 5 секунд, «текущая» секунда прибавляется из памяти — счётчики растут в реальном времени (только для локального сервера).

### Forge

Плейсхолдеров нет. В экосистеме Forge 1.20.1 нет мода, совместимого по API с `eu.pb4:placeholder-api` (он fabric-only), а альтернатив с той же семантикой не нашлось. Класс `StatifyPlaceholders` в моде сохранён как no-op, поэтому если появится подходящий placeholder-мод — интеграцию можно вернуть без правок остальной логики.

## Velocity: sticky-connect

`statify-velocity` не считает время и не регистрирует плейсхолдеров — он только запоминает, на каком сервере игрок был в последний раз, и при следующем входе перекидывает его туда же. Также на определённые версии подключаемые клиенты отправляет на нужный сервер. В случае невозможсти подключится, возвращает подключение с `velocity`

Отдельный конфиг `plugins/statify/config.yml`:

```yaml
config-version: '1.0.0'

database:
  type: 'none'          # mysql | mariadb | none — те же настройки, что и у paper/fabric
  host: 'localhost'
  port: 3306
  database: 'statify'
  username: 'statify'
  password: 'change_me'
  use-ssl: false
  pool-size: 4

redirect:
  # Включить редирект. При false плагин только пишет last_server, но никого не перекидывает.
  enabled: true
  # Проверять доступность сервера пингом перед редиректом (мс). 0 = не проверять.
  ping-timeout-ms: 1500
  # Серверы, на которые НЕЛЬЗЯ редиректить (напр. только-лобби). Пусто — редирект везде.
  blacklist: []
```

## Команды

Paper/Fabric/Forge — `/statify reload`: перечитать конфиг без пересоздания подключения к БД. Применяются `server-name`, `flush-interval-minutes`, вся секция `format`. При смене имени сервера накопленное время текущих сессий предварительно сбрасывается в БД под старым именем, затем переключается на новое.

Velocity — `/statify <reload|look|forget|set>`:

- `reload` — перечитать `config.yml` (без переподключения к БД).
- `look <player>` — показать `last_server` игрока. Работает с оффлайном: UUID сначала ищется в онлайн-списке Velocity, затем в `statify_players.name` (case-insensitive).
- `forget <player>` — обнулить `last_server` (следующий вход пойдёт через штатный `try:`). Тоже работает с оффлайном. Меняет только колонку `last_server` — `last_seen`, `last_ip`, `name` не трогаются.
- `set <player> <server>` — принудительно установить `last_server` для игрока. Сервер должен быть зарегистрирован в Velocity, иначе команда откажет. Требует, чтобы у игрока уже была строка в БД (то есть он хоть раз входил через прокси).

Для смены `database.*` нужен полный перезапуск сервера/прокси.

### Права

Paper: пермишен `statify.reload` (по умолчанию OP). Настраивается через LuckPerms и любые plugins с bukkit permissions.

Fabric: пермишен-нода `statify.command.reload`. Проверяется через [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api). Если permissions-мод не установлен — fallback на op level 3.

Forge: `/statify reload` требует op level 3. Отдельного permissions-API у Forge 1.20.1 нет.

Velocity: пермишен `statify.admin` (проверяется штатным `CommandSource#hasPermission`; в базовой Velocity консоль всегда получает `true`, для игроков подключается любой permissions-плагин — LuckPerms-velocity и т.п.).

С LuckPerms:
```
/lp user Steve permission set statify.command.reload true
```

## Как пишутся данные

Чтобы не молотить БД каждую минуту, время сохраняется:

1. **При выходе игрока** — вся его несохранённая сессия пишется в `statify_daily`.
2. **При остановке сервера** — flush всех активных сессий.
3. **Раз в `flush-interval-minutes`** — страховочный снапшот на случай падения сервера.
4. **При пересечении полуночи** — сессия аккуратно разбивается на дневные куски.

Плейсхолдеры продолжают показывать актуальное время, потому что живой счётчик прибавляется из памяти поверх БД-значения.
