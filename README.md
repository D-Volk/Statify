# Statify

Учёт игрового времени на Minecraft-сервере с записью в MySQL/MariaDB и набором плейсхолдеров: имя, UUID, всего / за день / за неделю / за месяц.

Два независимых модуля с общей БД и общей схемой:

- `statify-paper` — плагин под **Paper 1.20.4** и **1.21.x**. Интеграция с PlaceholderAPI.
- `statify-fabric` — мод под **Fabric 1.21.8** (loader 0.17.2, api 0.136.1). Интеграция с [Text Placeholder API](https://placeholders.pb4.eu/) от pb4.

## Сборка

Требуется JDK 21.

```bash
# Paper
cd statify-paper
./gradlew shadowJar
# → build/libs/statify-paper-1.0.0.jar

# Fabric
cd statify-fabric
./gradlew build
# → build/libs/statify-fabric-1.0.0.jar
```

## Установка

- Paper: положить jar в [paper/plugins](paper/plugins), сервер стартовать один раз (создастся `plugins/Statify/config.yml`), заполнить БД, перезапустить.
- Fabric: положить jar в [fabric/mods](fabric/mods), сервер стартовать один раз (создастся `config/statify/config.yml`), заполнить БД, перезапустить.

По умолчанию `database.type: none` — плагин/мод при этом выводит warn и отключается.

## Конфиг

`config-version` совпадает с версией плагина. При апгрейде плагина старый `config.yml` автоматически переименовывается в `config.old.<старая-версия>.yml`, а на его месте создаётся свежий.

```yaml
config-version: '1.0.0'
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

- `statify_players (uuid, name, last_seen)` — актуальное имя игрока по UUID.
- `statify_daily (server, uuid, day, seconds)` — накопленные секунды по дню и серверу.

Суммы за неделю/месяц/всё — агрегаты по `statify_daily`.

## Плейсхолдеры

### Paper (PlaceholderAPI)

| Плейсхолдер | Значение |
| --- | --- |
| `%statify_name%` | имя игрока |
| `%statify_uuid%` | UUID игрока |
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

Базовые: `%statify:name%`, `%statify:uuid%`, `%statify:server%`, `%statify:total%`, `%statify:day%`, `%statify:week%`, `%statify:month%`.

Кросс-серверные — через аргумент после `/`:

- `%statify:total/<server>%`
- `%statify:day/<server>%`, `%statify:week/<server>%`, `%statify:month/<server>%`

Пример: `%statify:total/survival%`.

Топ по общему времени (N от 1 до 20):

- `%statify:top_name/<N>%`, `%statify:top_uuid/<N>%`, `%statify:top_time/<N>%`, `%statify:top_seconds/<N>%`
- с указанием сервера: `%statify:top_name/<N>/<server>%` и т.д.

Пример: `%statify:top_name/1%`, `%statify:top_time/5/survival%`. Кэш топа — 30 секунд.

Результаты обычных плейсхолдеров кешируются на 5 секунд, «текущая» секунда прибавляется из памяти — счётчики растут в реальном времени (только для локального сервера).

## Команды

`/statify reload` — перечитать конфиг без пересоздания подключения к БД. Применяются `server-name`, `flush-interval-minutes`, вся секция `format`. При смене имени сервера накопленное время текущих сессий предварительно сбрасывается в БД под старым именем, затем переключается на новое.

Для смены `database.*` нужен полный перезапуск сервера.

### Права

Paper: пермишен `statify.reload` (по умолчанию OP). Настраивается через LuckPerms и любые plugins с bukkit permissions.

Fabric: пермишен-нода `statify.command.reload`. Проверяется через [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api). Если permissions-мод не установлен — fallback на op level 3.

С LuckPerms на Fabric:
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
