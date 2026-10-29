# Архитектура Blocknet

Документ описывает тело бота (мод NeoForge) и его связь с мозгом. Почему выбраны именно такие
решения, записано в [ADR](adr). Места, где точное имя метода Minecraft 1.21.1 ещё нужно проверить
на прототипе, помечены «(проверить)».

## Коротко

- Тело — настоящий `ServerPlayer` с заглушкой вместо сетевого соединения
  ([ADR-0001](adr/0001-server-side-player-body.md)). Клиентам мод не нужен.
- Действия идут через тот же серверный код, что у живого игрока: `ServerPlayerGameMode`, меню,
  атрибуты дальности.
- Что перед ним, бот видит лучами из своих глаз ([ADR-0002](adr/0002-honest-perception.md)). Для
  ориентации у него есть карта: структуры и пещеры из загруженных чанков, живые мобы в 48 блоках
  без проверки видимости ([ADR-0011](adr/0011-assisted-perception.md)). В режиме `full` (по умолчанию)
  `obtain` знает и руду в загруженных чанках ([ADR-0012](adr/0012-full-perception.md)).
- Мозг — отдельный процесс, связь по TCP JSON lines (JSON-RPC 2.0) с версией протокола
  ([ADR-0003](adr/0003-brain-outside-mod.md)). LLM не вызывается на каждом тике.
- Поиск пути — Automatone, вынесенный из PlayerEngine в отдельный LGPL-модуль
  ([ADR-0004](adr/0004-pathing-from-automatone.md)).
- Тело не знает модов по имени: общий слой (capabilities, меню, реестры), адаптеры только там,
  где общего слоя не хватает ([ADR-0005](adr/0005-mod-agnostic-body.md)).

## 1. Общая схема

```
 Мозг (процесс, LLM)                    Тело (мод NeoForge, сервер)
 ┌────────────────────┐   TCP lines    ┌───────────────────────────────────────┐
 │ агент, память,     │◄──JSON-RPC───► │ bridge: очередь команд, события       │
 │ знания, диалог,    │  localhost     │   │ (сетевой поток → server.execute) │
 │ MCP-обёртка        │                │   ▼                                   │
 └────────────────────┘                │ TaskManager ── Reflexes (без LLM)     │
                                       │   │ навыки: goto, mine, craft, ...    │
                                       │   ▼                                   │
                                       │ Controller (голова, ноги, руки)       │
                                       │   │        ▲                          │
                                       │   ▼        │                          │
                                       │ BotPlayer (ServerPlayer)  Sensor+Memory│
                                       │ Pathing (Automatone, LGPL-модуль)     │
                                       │ Machines (capabilities, меню, слоты)  │
                                       │ Knowledge dump (рецепты, теги, лут)   │
                                       └───────────────────────────────────────┘
```

Потоки:

- Всё, что трогает мир, выполняется на серверном потоке.
- Сетевые потоки передают вызовы через `server.execute`. Ответы и события возвращаются после выполнения на серверном потоке.
- Тяжёлая сводка для LLM собирается из неизменяемых снимков вне серверного потока.

## 2. Тело: `BotPlayer extends ServerPlayer`

### Появление

1. `GameProfile` с именем и UUID: офлайновый `UUIDUtil.createOfflinePlayerUUID(name)` или
   фиксированный из конфига. Скин — свойство `textures` в профиле, без него будет стандартный.
2. `new BotPlayer(server, level, profile, ClientInformation.createDefault())`.
3. `server.getPlayerList().placeNewPlayer(new BotConnection(...), bot, new CommonListenerCookie(...))`.
   `BotConnection` — `Connection` без настоящего канала, исходящие пакеты выбрасываются.
   `placeNewPlayer` сам загружает `playerdata/<uuid>.dat`: инвентарь и позиция сохраняются штатно.
4. Свой реестр ботов в `SavedData`: кто заспавнен, владелец, режим. При старте сервера боты
   возрождаются, при остановке корректно выходят.

### Тик

У живого игрока физику запускает клиент пакетами движения. У бота клиента нет, поэтому `tick()`
вызывает `super.tick()` и затем `doTick()` (`Player.tick → aiStep → travel`), а раз в ~10 тиков —
`connection.resetPosition()` и `getChunkSource().move(this)`. Чанки вокруг бота грузятся как вокруг
игрока.

### Смерть

Обычная смерть, предметы выпадают. Возрождение — через штатную клиентскую команду «возродиться»,
затем событие мозгу.

### Совместимость в ATM10

| Тема | Что делать |
|---|---|
| Приваты FTB Chunks / FTB Teams | Бот — игрок со своим UUID, добавить его в команду владельца |
| Сон | Бот считается игроком и мешает пропуску ночи: рефлекс «ложиться вместе с владельцем» или исключение из подсчёта (проверить) |
| Спавн мобов | Вокруг бота спавнятся мобы, как вокруг игрока |
| `instanceof FakePlayer` | Бот не наследует `FakePlayer`, моды не режут ему права |
| online-mode и whitelist | Имя бота не совпадает с реальным аккаунтом, бот в whitelist |
| Клиент | Мод нужен только на сервере. В одиночной игре он стоит в клиенте, там встроенный сервер |

## 3. Действия

### 3.1 Controller

Один владелец головы, ног и рук на тик.

- Голова: целевые yaw/pitch с ограничением скорости поворота (порядка 20–40° за тик, подобрать).
  Сенсор смотрит туда, куда реально повёрнута голова.
- Ноги: `zza`, `xxa`, прыжок, присед, бег. Физику считает `doTick()`.
- Руки: выбранный слот хотбара, текущее действие.

Арбитраж головы: рефлекс > выполняемое действие > путь > осмотр.

### 3.2 Примитивы

Тот же код, что при пакетах настоящего клиента.

| Действие | Вызов | Проверки |
|---|---|---|
| Ломать блок | `gameMode.handleBlockBreakAction(pos, START…)`, затем прогресс `state.getDestroyProgress(...)` каждый тик, при ≥1 — `STOP_DESTROY_BLOCK` | Скорость по инструменту и эффектам; дальность `blockInteractionRange()`; грань видна лучом |
| Клик по блоку / поставить | `gameMode.useItemOn(player, level, stack, hand, hit)` | Дальность, видимость, зоны |
| Использовать предмет | `gameMode.useItem(...)`, удержание N тиков, затем `releaseUsingItem()` | Кулдауны |
| Атака / взаимодействие | `player.attack(e)` с `getAttackStrengthScale`; `player.interactOn(e, hand)` | `entityInteractionRange()`, линия видимости |
| Выбросить, сменить слот | `drop(...)`, `getInventory().selected` | — |

Обработчик пакетов сервера обходим, поэтому дальность и видимость проверяем сами. Дальность берём
из атрибутов игрока, так учитываются модовые удлинители досягаемости.

### 3.3 Меню

- Открыть: `useItemOn` по машине, сервер сам откроет меню → `player.containerMenu`.
- Слоты: `containerMenu.clicked(slot, button, ClickType, player)` — одинаково для любого мода.
- Кнопки: `containerMenu.clickMenuButton(player, id)`.
- Крафт: серверная обработка книги рецептов (`handlePlaceRecipe`) раскладывает ингредиенты сама.
- Закрыть: `player.closeContainer()`.
- Модовые кнопки и бинды, которые работают своими пакетами, общий слой не покрывает: для них
  опциональные адаптеры (раздел 3.4). Общий путь «пакет в соединение» может упереться в проверку
  согласованных каналов NeoForge (проверить).

Мозгу отдаётся `menu.get`: тип меню, слоты, известные кнопки, плюс энергия и прогресс через
capabilities.

### 3.4 Модовый контент без хардкода

Тело не знает модов по имени ([ADR-0005](adr/0005-mod-agnostic-body.md)).

- Общий слой: блоки и предметы по реестру и тегам, машины через capabilities NeoForge (предметы,
  жидкости, энергия по сторонам), интерфейсы через меню и слоты, рецепты через `RecipeManager`.
- Адаптер под мод — только если общий слой физически не даёт нужного (содержимое сети AE2 терминал
  показывает своими пакетами, а не слотами). Адаптер реализует общий интерфейс (`StorageNetwork`),
  включается только при загруженном моде и не добавляет логики в тело.
- Реальные моды в репозитории — только тестовая среда для проверки общего слоя.

### 3.5 Навыки и задачи

- Навык — детерминированный автомат: `goto`, `follow`, `mine_visible`, `collect_drops`,
  `branch_mine`, `explore_frontier`, `craft`, `smelt`, `deposit`, `withdraw`, `place_blueprint`.
- TaskManager: `task_id`, фазы (`checking → searching → approaching → mining → collecting →
  verifying → completed`, плюс `blocked`, `failed`, `cancelled`); ресурсы «руки, ноги, голова»;
  отмена в тот же тик без LLM; версия команды, поздний ответ мозга на отменённую задачу отбрасывается.
- Рефлексы вытесняют задачу и возвращают её: еда, защита, выход из лавы, вода при падении, воздух,
  подбор своего дропа.
- Безопасность: зоны, защита поставленных игроком блоков, журнал действий для отката, не бить
  игроков и прирученных животных.

## 4. Сенсор честного зрения

Карта мира и радар (`map/`, ADR-0011) дополняют зрение: `MapScanner` по одному загруженному чанку
за тик пишет в `MapMemory` (SavedData уровня) структуры из `StructureStart` и выборку пещерного
воздуха с входами (подземный воздух с небесным светом); `Radar` даёт мобам рефы `e#` и живые
позиции. Инструмент `map` — сетка 9×9 чанков, места `v#`/`k#` и мобы. Ниже — само зрение.

### Параметры

Глаза — `getEyePosition()` и поворот головы. Поле зрения 90°×≈59°, дальность 48 блоков (не больше
view distance). `perception_mode`: `honest` по умолчанию, `debug_omniscient` только для отладки.

### Прозрачность

- `level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, fluid, bot))`. У стекла визуальная
  форма пустая, луч проходит насквозь (проверить на 1.21.1).
- Переопределения тегами датапака: `blocknet_ai:see_through` (модовое стекло, решётки) и
  `blocknet_ai:opaque`.
- Жидкости: первый луч отмечает поверхность воды или лавы, сквозь воду луч идёт ограниченно.

### Каналы

1. Фоновый обзор: сетка 64×36 лучей, размазанная на 4 тика, полный кадр 5 раз в секунду. На каждом
   проходе сетка слегка случайно сдвигается. Бюджет — порядка 1–1,5 мс на бота за тик (замерить).
2. Проверка кандидатов под цель: отбор секций по палитре (`maybeHas`, проверить), позиции в поле
   зрения, лучи к граням, обращённым к глазам. Скрытые кандидаты не влияют ни на поворот головы,
   ни на маршрут.
3. Точные лучи перед действием.

### Прочее

- Сущности: поле зрения плюс собственный луч (ванильный `hasLineOfSight` считает стекло
  непрозрачным).
- Свет: при освещённости грани < 4 дальше ~4 блоков без ночного зрения или факела блок не
  распознаётся (`dim_unknown`).
- Слух: `PlayLevelSoundEvent` — тип звука, примерное направление и расстояние с шумом.
- Машины: `inspect` через capabilities только для видимой машины в досягаемости (аналог Jade).

### Память

- Запись: `(dimension, pos | entity_uuid) → semantic_id, state, provenance (VISIBLE_NOW /
  REMEMBERED / API_OBSERVED / INFERRED), last_seen_tick, sensor_seq, evidence`.
- О скрытых изменениях бот не узнаёт до повторного взгляда, кроме изменений, сделанных им самим.
- Группировка в объекты: залежь, печь, проход, граница неизвестного.
- Мозгу уходит сводка, а не воксели.

### Поиск

- Граница неизвестного: осмотреться → подойти к краю → осмотреться.
- Ветвистая шахта на высотах из датапака worldgen сборки (знание «как из вики»).
- Поиск пути видит реальную геометрию рядом с ботом, но цель и направление поиска выбираются только
  из памяти. Тест на утечку проверяет именно выбор целей.

## 5. Протокол тело ↔ мозг

Реализованный транспорт — TCP на `127.0.0.1`, JSON-RPC 2.0, UTF-8, один JSON-объект на строку
([ADR-0008](adr/0008-bridge-json-lines.md) заменяет WebSocket из ADR-0003). Только `java.net` и
штатный Gson, без новой библиотеки. Сервер запускается и останавливается вместе с Minecraft;
один accept-поток, reader-поток на соединение, каждый вызов через `server.execute`.

`config/blocknet_ai-bridge.json` содержит `port` (25599 по умолчанию) и случайный `token`,
создаваемый при первом запуске. До успешного `hello` остальные вызовы отклоняются.

```json
→ {"jsonrpc":"2.0","id":1,"method":"hello","params":{"protocol":1,"token":"…"}}
← {"jsonrpc":"2.0","id":1,"result":{"protocol":1,"bots":["Kirill"],"tools":[{"name":"goto","description":"Walk to exact block coordinates.","params":{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"z":{"type":"integer"}},"required":["x","y","z"],"additionalProperties":false}}]}}
→ {"jsonrpc":"2.0","id":2,"method":"tool.call","params":{"bot":"Kirill","tool":"goto","args":{"x":10,"y":64,"z":20}}}
← {"jsonrpc":"2.0","id":2,"result":{"task_id":"…"}}
← {"jsonrpc":"2.0","method":"event","params":{"type":"task.finished","bot":"Kirill","task_id":"…","result":"[Kirill] arrived"}}
```

Методы: `hello {protocol, token}` и `tool.call {bot, tool, args}`. `hello.tools` — единый реестр
с описаниями и JSON Schema параметров. Инструменты используют те же команды/контроллеры и текст,
что `/blocknet`; отдельной логики выбора следующего шага у моста нет.

| Инструменты | Параметры args | Результат |
|---|---|---|
| `status`, `inv` | `{}` | `{result: "однострочный текст"}` |
| `changes` | `{}` | Изменения с прошлого changes этого соединения; baseline команд и других соединений независим |
| `memory` | `{filter?}` | Последние наблюдения контейнеров |
| `plan` | `{item, count?, mode?: "craft" или "get"}` | План без выполнения; count по умолчанию 1 |
| `stop` | `{}` | Немедленная отмена текущей задачи |
| `say` | `{text}` | Всем игрокам как `<BotName> text` |
| `look` | `{}` | Задача: осмотреться честными лучами |
| `come`, `follow` | `{player}` | Задача к игроку по имени в том же измерении; follow до stop/замены |
| `goto`, `mine`, `use`, `look-in` | `{x, y, z}` | Задача на один блок |
| `craft`, `get` | `{item, count?}` | Задача; count по умолчанию 1 |
| `deposit`, `withdraw` | `{x, y, z, filter, count?}` | Задача; filter: item id, #tag, all, junk; без count — всё подходящее |

Обычные инструменты отвечают `{result:"…"}`. Задачи сначала отвечают `{task_id}`, затем ровно
один `event {type:"task.finished", bot, task_id, result}` с существующим итоговым отчётом, включая
ошибку, остановку или замену задачи. Итог получают все аутентифицированные соединения, чтобы
основной мозг и MCP-процесс могли работать одновременно. Результат всегда идёт после `task_id`.

Другие уведомления: `event {type:"chat", from, text}` для чата настоящих игроков
(`ServerChatEvent`, без ботов) и `event {type:"bot.died", bot}`. Без мозга локальная игра не меняется;
обрыв не отменяет задачу и не повторяет её. Ошибки JSON-RPC: `-32700` неверный JSON, `-32600`
неверный запрос, `-32001` неверное рукопожатие, `-32601` неизвестный метод, `-32602` неверный вызов.

Мозг (`brain/`, Node 22 без npm-зависимостей) сначала проверяет короткие ru/en правила
stop/come/follow/look; только остальные обращения к имени/алиасу вызывают `claude -p --model haiku`.
MCP stdio процесс отдаёт этот же реестр для одного бота и ждёт `task.finished` до 10 минут, поэтому
для модели один вызов инструмента — действие вместе с исходом. Системный промпт короткий и
неизменный, встроенные инструменты Claude отключены, новые сообщения стоят в очереди, stop
действует сразу. Сессия на бота сохраняется до 30 ходов, расход и число MCP-вызовов — в JSONL.
Эпохи, replay/idempotency и остальные проектные методы ADR-0003 пока не реализованы.

## 6. Поиск пути: вынос Automatone

`automaton/` в PlayerEngine — 279 файлов, ~28k строк, LGPL-3.0, уже на именах Mojang.

Оставляем: `pathing/calc`, `pathing/movement` и 8 видов движения, `api/pathing/goals`,
`behavior/PathingBehavior`, `LookBehavior`, `utils/InputOverrideHandler`, `BlockStateInterface`,
`BlockBreakHelper`, `BlockPlaceHelper`, `ToolSet`, процессы `CustomGoalProcess` и `FollowProcess`
(позже `BuilderProcess`).

Выкидываем: `MineProcess`, `GetToBlockProcess`, `ExploreProcess`, `cache/WorldScanner` (сканирование
чанков — рентген), `ContainerMemory`, `FishingProcess`, клиентские `GuiClick` и `PathRenderer`,
команды `command/**`.

| Зависимость | Замена |
|---|---|
| `PlayerEngine.*` (логгер, исполнитель, константы) | `PathingHost` |
| Architectury (2 импорта) | `RegisterCommandsEvent`, `ModList` / `FMLPaths` |
| `IBaritone.KEY` | Поле в `BotPlayer` или `AttachmentType` |
| Инвентарь и взаимодействие моба | Интерфейс `BotHands` поверх `Inventory` и `ServerPlayerGameMode`, правки в 12 файлах |
| Миксины `baritone/*` | Убрать специфичные для мобов; аксессор `ServerChunkCache` заменить публичным `getChunkNow` (проверить) |
| `PlayerPlacedBlockStore` | Интерфейс `BlockProtection` |
| Управление ногами и головой | Через наш Controller |

Порядок: подпроект `pathing` под LGPL-3.0 → компиляция без PlayerEngine → `BotHands` → GameTest
(яма, столб, копание вниз, лава, дверь) → подключение к TaskManager.

## Планирование крафта (ADR-0007)

`crafting/` — отдельная LGPL-3.0-or-later библиотека (`dev.blocknet.crafting`), как `pathing/`:
`GAMELIBRARY`, compileOnly для `mod/`, sourceSet в dev-запусках, jarJar в готовом моде.
`RecipeCatalog` превращает рецепты сервера в `Process`, `ItemKey` сохраняет data components.
`CraftingCalculation` адаптирует дерево AE2: сначала инвентарь, затем дочерние симуляции рецептов,
замены ингредиентов, защита от рекурсии, возврат контейнеров и второй проход с недостающим.
`CraftPlan` — неизменяемый результат со списком шагов в порядке исполнения и компактным текстом.
`PlannerCatalogs` кэширует индекс до смены рецептов; каждый расчёт получает копию инвентаря.
Пока только синхронный расчёт через `/blocknet plan`, без исполнения, восприятия станций и моста
к мозгу. Новые API не зависят от типов сети/CPU AE2 и не называют модов.

## 7. Раскладка мода

```
crafting/src/main/java/dev/blocknet/crafting/  LGPL planner, recipes, simulation, plan DTO
pathing/                                    LGPL navigation library
mod/src/main/java/dev/blocknet/
  body/        BotPlayer, BotConnection, BotSpawner, BotRegistry (SavedData)
  control/     Controller, BotHands
  actions/     Break, UseOn, Use, Attack, Interact, Menu
  tasks/       TaskManager, TaskStateMachine, Reflexes
  skills/      Goto, Follow, MineVisible, BranchMine, ExploreFrontier, CollectDrops, Craft, Smelt, Deposit
  sensor/      VisionSensor, OpticalPolicy, CandidateCheck, EntityVision, LightRule, Hearing
  memory/      ObservationStore, ObjectGraph, Summarizer
  machines/    CapabilityInspector, StorageNetwork, adapters/<мод> (опционально)
  knowledge/   RecipeDump, TagDump, LootDump, WorldgenHeights
  bridge/      WsServer, JsonRpc, EventBus, Auth
  safety/      Zones, ProtectedBlocks, ActionLog
```

## 8. Порядок MVP

1. Бот появляется, ходит и копает; команды отладки через чат.
2. Automatone, `goto` и `follow`.
3. Сенсор: фоновый обзор, память, отладочная визуализация.
4. Навык «накопай угля» целиком без LLM.
5. Мост и простой мозг.
6. Меню и capabilities (общий слой), сети хранения только на чтение.
