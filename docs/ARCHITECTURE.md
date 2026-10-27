# Архитектура Blocknet

Документ описывает тело бота (мод NeoForge) и его связь с мозгом. Почему выбраны именно такие
решения, записано в [ADR](adr). Места, где точное имя метода Minecraft 1.21.1 ещё нужно проверить
на прототипе, помечены «(проверить)».

## Коротко

- Тело — настоящий `ServerPlayer` с заглушкой вместо сетевого соединения
  ([ADR-0001](adr/0001-server-side-player-body.md)). Клиентам мод не нужен.
- Действия идут через тот же серверный код, что у живого игрока: `ServerPlayerGameMode`, меню,
  атрибуты дальности.
- Восприятие честное: бот видит только лучами из своих глаз, у каждого факта есть происхождение
  ([ADR-0002](adr/0002-honest-perception.md)).
- Мозг — отдельный процесс, связь по WebSocket (JSON-RPC 2.0) с версией протокола
  ([ADR-0003](adr/0003-brain-outside-mod.md)). LLM не вызывается на каждом тике.
- Поиск пути — Automatone, вынесенный из PlayerEngine в отдельный LGPL-модуль
  ([ADR-0004](adr/0004-pathing-from-automatone.md)).
- Тело не знает модов по имени: общий слой (capabilities, меню, реестры), адаптеры только там,
  где общего слоя не хватает ([ADR-0005](adr/0005-mod-agnostic-body.md)).

## 1. Общая схема

```
 Мозг (процесс, LLM)                    Тело (мод NeoForge, сервер)
 ┌────────────────────┐   WebSocket    ┌───────────────────────────────────────┐
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
- WebSocket живёт в своём потоке и только кладёт команды в очередь, она разбирается в
  `ServerTickEvent.Post`. Ответы возвращаются через `CompletableFuture`.
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

Транспорт — WebSocket на `127.0.0.1`, JSON-RPC 2.0, токен из конфига, один мозг на бота. Библиотека
Java-WebSocket вкладывается в jar мода (jarJar), JSON через Gson. MCP-обёртка живёт в мозге.

Рукопожатие:

```json
→ {"jsonrpc":"2.0","id":1,"method":"hello","params":{"protocol":1,"bot":"Kirill","token":"…"}}
← {"jsonrpc":"2.0","id":1,"result":{"epoch":7,"bot_ref":"p:2f1c…","perception_mode":"honest",
   "modpack_fingerprint":"atm10-6.0-9c1e…","knowledge_dir":"config/blocknet_ai/knowledge/9c1e…",
   "adapters":["storage_network:ae2"],"skills":["goto","follow","mine_visible","branch_mine","craft","smelt","deposit"]}}
```

| Группа | Методы |
|---|---|
| Восприятие | `observe(focus?)`, `inspect(ref)`, `find_visible(kind, limit)`, `memory.query(kind, near?, limit)`, `look(dir \| ref)` |
| Задачи | `task.start(goal, request_id, preempt?)`, `task.cancel(id)`, `task.status(id)`, `task.list()` |
| Инвентарь и меню | `inventory.get()`, `menu.get()`, `menu.click(...)`, `menu.close()` |
| Машины и хранилища | `machine.inspect(ref)` (capabilities), `storage.query(item)`, `storage.craft(item, n, request_id)` (сети хранения, через адаптер при загруженном моде) |
| Речь | `chat.say(text, to?)` |
| Управление | `zones.get/set`, `bot.mode(follow \| idle \| autonomous)` |

События (склеиваются окнами ~250 мс): `chat.heard`, `task.accepted/progress/blocked/completed/
failed/cancelled`, `perception.noticed`, `status`, `damage`, `death`, `sound.heard`,
`inventory.changed`, `menu.opened/closed`, `world.epoch_changed`.

Пример «накопай 16 угля»:

```json
→ {"jsonrpc":"2.0","id":42,"method":"task.start","params":{"request_id":"u-105",
   "goal":{"skill":"gather","item":"minecraft:coal","source":"mine",
           "amount":{"mode":"additional","value":16},
           "constraints":{"stay_near":"owner","max_duration_s":300}}}}
← {"jsonrpc":"2.0","id":42,"result":{"task_id":"t-42","state":"accepted"}}
← {"jsonrpc":"2.0","method":"event","params":{"epoch":7,"seq":881,"tick":183220,
   "type":"task.progress","task_id":"t-42","phase":"searching","detail":"no visible coal; exploring frontier"}}
← {"jsonrpc":"2.0","method":"event","params":{"epoch":7,"seq":894,"type":"task.blocked","task_id":"t-42",
   "reason":"tool_broken","need":"tag:minecraft:pickaxes","options":["craft_pickaxe","withdraw_from_storage","abort"]}}
```

- Ссылки: `b:<dim>:<x>,<y>,<z>#<gen>`, `e:<uuid>`, `g:coal_patch_12`; ссылка из прошлой эпохи
  отклоняется с `stale_ref`.
- Ошибки: `not_visible`, `out_of_reach`, `busy`, `stale_ref`, `forbidden_zone`, `unsupported`,
  `unknown_item`.
- Повторный `task.start` с тем же `request_id` возвращает ту же задачу.
- При обрыве связи бот доделывает задачу и следует за владельцем.

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
