# Разработка

## Требования

- JDK 21. Gradle берёт JDK из `JAVA_HOME`; системная Java 8 не подойдёт.
- Первая сборка скачивает Gradle, NeoForge и Minecraft и занимает несколько минут.

```bash
export JAVA_HOME="$HOME/.jdks/jdk-21.0.12.1+1"   # путь к своему JDK 21
./gradlew build             # сборка мода: mod/build/libs/
./gradlew :mod:runClient    # клиент с модом (встроенный сервер)
./gradlew :mod:runServer    # выделенный сервер, рабочая папка mod/run/
./gradlew :mod:runGameTestServer   # автотесты GameTest
```

Чтобы играть с модом в своём лаунчере (например, в отдельном инстансе Prism), укажи папку `mods`
инстанса в `~/.gradle/gradle.properties`:

```properties
blocknet.deployModsDir=C:/Users/<ты>/AppData/Roaming/PrismLauncher/instances/<инстанс>/minecraft/mods
```

Тогда каждый `./gradlew build` кладёт туда свежий jar и убирает старые версии мода. Запущенная игра
подхватит его только после перезапуска.

## Отладочные команды

Ботом можно управлять командами независимо от мозга (нужны права оператора):

```
/blocknet spawn <имя>          бот входит в игру там, где стоит вызвавший
/blocknet despawn <имя>        бот выходит, его инвентарь сохраняется как у игрока
/blocknet list                 боты, их позиции и текущие задачи
/blocknet status [имя]         тело (здоровье, голод, эффекты, позиция) и текущая задача
/blocknet inv [имя]            предметы, слоты и сводка инструментов, еды, строительных блоков
/blocknet changes [имя]        изменившиеся разделы и разница предметов с предыдущего вызова
/blocknet plan [craft|get] [имя] <item> [count]  посмотреть план, по умолчанию craft; count по умолчанию 1
/blocknet craft [имя] <item> [count] изготовить count НОВЫХ предметов через рецепты, count по умолчанию 1
/blocknet get [имя] <item> [count]   получить в инвентаре хотя бы count предметов, count по умолчанию 1
/blocknet knowledge [имя] <item|block> высоты руды, частота, инструмент и дроп из данных сервера
/blocknet mine_ore [имя] <item|block> [count] добыть ещё count предметов руды; ветки, бюджет 10 минут
/blocknet obtain [имя] <item> [count] получить хотя бы count: сундуки, крафт/плавка, добыча сырья
/blocknet goto [имя] <x y z>   идти к точному блоку
/blocknet come [имя]           подойти рядом на землю под вызвавшим (радиус 2 блока)
/blocknet follow [имя]         следовать за вызвавшей сущностью до stop или новой задачи
/blocknet mine [имя] <x y z>   подойти, сломать блок с честной скоростью, подобрать дроп
/blocknet use [имя] <x y z>    подойти и кликнуть блок правой кнопкой (машины открывают меню)
/blocknet look [имя]           панорама: объекты, сущности, фронтиры и статистика зрения
/blocknet see [имя]            та же панорама: ref, вид, направление, расстояние и число клеток
/blocknet look-in [имя] <x y z> открыть контейнер, запомнить видимые слоты меню и закрыть
/blocknet deposit [имя] <x y z> <filter> [count] положить предметы через клики меню
/blocknet withdraw [имя] <x y z> <filter> [count] забрать предметы через клики меню
/blocknet memory [имя] [filter] показать последние наблюдения контейнеров
/blocknet close [имя]          закрыть открытое меню
/blocknet stop [имя]           отменить задачу
```

Зрение (`dev.blocknet.vision`) работает на серверном потоке: до 64 блоков и не дальше серверной
прорисовки. Незагруженные чанки остаются неизвестными. Стекло пропускает лучи, листья заслоняют
вид, вода пропускает до 6 метров. Грань дальше 4 метров при свете <4 сохраняется как `dark`, без
id блока. Содержимое контейнеров известно только через меню. Карта и ссылки объектов сохраняются
по UUID; предел — 512 секций, с вытеснением давно не виденных. `see` печатает p50/p95 полного кадра
(накопленное время работы без ожидания тиков), p50/p95 и максимум на тик, оценку размера карты.
Бюджет: `-Dblocknet.visionBudgetNanos=1500000`. Статистика включает холодный старт и паузы JVM.

Без имени команда уходит ближайшему к вызвавшему боту.
Бот — настоящий игрок: выдать предметы можно обычной `/give <имя_бота> minecraft:iron_pickaxe`
или `/give <имя_бота> minecraft:bread 16`.
`mine` сразу отвечает `blocked: need a tool for …`, если блоку нужен правильный инструмент, а
в инвентаре его нет; бот остаётся на месте. Почти сломанный подходящий инструмент не блокирует
добычу, но итоговый отчёт содержит `(tool almost broken)`.

Состояние в `state/` собирается на серверном потоке только по запросу, без обработки каждого тика.
`BodyStateTracker.snapshot()` возвращает неизменяемые текстовые разделы `body`, `inventory`, `task`
с независимыми ревизиями; `since(snapshot.revisions())` возвращает только изменившиеся разделы и
разницу количества предметов. Ревизия растёт только при изменении текста. История инвентаря хранит
64 ревизии: неизвестная или устаревшая база даёт полный инвентарь с `inventoryReset=true`.
Изменения выбранного слота и экипировки видны в тексте раздела, даже когда состав предметов тот же.
`changes` хранит одну базу на бота, первый вызов показывает всё, повторный без изменений —
`no changes`; `status` и `inv` эту базу не сдвигают. Это локальный API состояния без транспорта.
Номера слотов начинаются с нуля; свободные слоты считаются только в 36 слотах основного инвентаря.
Сводка еды считает сумму nutrition × количество, строительные блоки — по общей политике
`InventoryBehavior.isAcceptableThrowaway` (включая её теги, настройки и исключения).

`look` поворачивает голову на полный круг с небольшим движением вверх/вниз за 20 тиков.
Лучи из глаз (`VISUAL`, до 16 блоков) распознают только первый блок, сохраняя правило света:
дальше 4 блоков нужна освещённость не ниже 4. Отчёт перечисляет станции и контейнеры с координатами;
содержимое контейнера отмечено как `known`, `unknown` или `stale` (снимок старше пяти игровых минут).
Осмотр ничего не открывает. `WorldMemory` хранит измерение, позицию, ID блока, вид объекта и тик
последнего наблюдения в отдельном `SavedData` по UUID бота, как `ContainerMemory`. Память обновляют
также лучи при прицеливании для действий. Увиденный исчезнувший или заменённый блок забывается;
скрытые изменения память не меняют. Верстаки и печи распознаются по общим тегам NeoForge,
остальные станции — по ванильным классам; контейнеры — по `Container` или item handler capability.

`look-in`, `deposit` и `withdraw` подходят в досягаемость, проверяют луч к блоку и открывают его
через `useItemOn`. Содержимое читается только из активных слотов открытого меню, без чтения
инвентаря block entity. Двойной сундук — одна запись независимо от открытой половины. Поддерживаются
бочки, шалкеры и модовые контейнеры с обычными слотами меню. Закрытый, запертый или защищённый
контейнер даёт один `failed: …`; задача всегда закрывает меню.

`filter` — ID предмета (`minecraft:iron_ingot` или `iron_ingot`), тег (`#c:ingots/iron`), `all` или
`junk`. Политика `ContainerFilter.junk` оставляет инструменты, броню, еду и строительные блоки,
которые принимает путь через `InventoryBehavior.isAcceptableThrowaway`. Без `count` переносится
всё подходящее; с `count` — ровно указанное суммарное количество. Место проверяется для всей партии
перед первым переносом; если места не хватает, предметы остаются на месте. Между кликами 3 тика,
как в крафте. Частичный перенос из слота результата, куда нельзя вернуть остаток, честно
отклоняется до кликов; перенос всего стака разрешён. Пример: `/blocknet withdraw Kirill 12 64 -30 minecraft:iron_ingot 3`.

`ContainerMemory` хранит измерение, координаты, ID блока, количества с полными data components и
тик последнего наблюдения. Данные — отдельный `SavedData` в overworld по стабильному UUID бота:
после перезапуска и спавна с тем же именем память возвращается. Обновления происходят только через
открытое меню, в том числе при `use` и работе печи у `CraftExecutor`. Скрытые изменения памяти
не меняют. Увиденный сломанный или заменённый контейнер забывается; невозможность открыть
запертый контейнер сохраняет прошлое наблюдение. `memory` показывает возраст в игровом времени,
а необязательный фильтр выбирает записи с подходящими предметами. `ContainerMemory.whereIs(ItemKey)`
даёт компактный ответ для будущего мозга, `sources(bot)` — источники текущего измерения по расстоянию.

`CraftingCalculation.plan(..., availableStations, rememberedSources, mode)` принимает обычные данные
`RememberedSource` (измерение, packed position, ID блока, `ItemKey -> count`), без зависимости от
мода. Сначала расходуется инвентарь, затем память ближайших контейнеров. В тексте плана перед
крафтом появляются шаги `1. withdraw 3 iron_ingot from chest 12 64 -30`. Режим `CRAFT` сохраняет
исходный запас целевого предмета в инвентаре и контейнерах: весь заказ производится рецептами,
а новые результаты, остатки партий и побочные продукты учитываются отдельно от старого запаса.
Режим `OBTAIN` разрешает покрывать цель запасами; перегрузки без режима означают `CRAFT`.
Ингредиенты в обоих режимах берутся из инвентаря, затем ближайших контейнеров, затем производятся.
Исполнитель выполняет переносы
той же задачей `withdraw`, без промежуточных отчётов. Если запасы изменились, новое открытие
обновляет память и допускается один общий повторный расчёт; затем — один итоговый отчёт.

`plan` только рассчитывает: предметы и меню не меняются, к мозгу инструмент пока не подключён.
Например, `/blocknet plan craft Kirill minecraft:wooden_pickaxe` или
`/blocknet plan get Kirill minecraft:iron_ingot 5`. Старый `/blocknet plan Kirill <item> [count]`
равнозначен `plan craft`. Первая строка — `plan craft <n> <item>: …` или `plan get <n> <item>: …`.
Результат: `ok`, `missing`, `no_recipe` или `limit`,
шаги по порядку с ID рецептов, станциями, количеством операций, входами и выходами; `use`,
`missing` (теги как `#minecraft:planks`), `left`, `stations`. Для кирки вместе с новым верстаком
нужны 9 досок: 3 бревна достаточно, 2 — нет. При готовом верстаке хватает 2 брёвен.
Учитываются станции из инвентаря и запомненные станции текущего измерения в пределах 24 блоков.
Печи используют запас топлива по burn time NeoForge с сохранением остатка горения между шагами;
костёр топлива не требует. Другие типы рецептов дают подсказку `can be made in <type>`.
Индекс рецептов кэшируется и обновляется после reload. Поиск на серверном потоке ограничен
50 мс, глубиной 48, 32 вариантами и 20 000 операциями; превышение даёт `search limit`.
Лимит времени кооперативный: вызовы методов рецептов модов прервать внутри невозможно.
Первичное построение индекса выполняется отдельно от поиска, до начала отсчёта бюджета.

`craft` означает изготовить новые предметы, даже если они уже есть: при 5 слитках
`/blocknet craft Kirill iron_ingot 3` плавит ещё 3 и оставляет 8. Целевой предмет из сундука
не забирается; ингредиенты из памяти разрешены. `get` означает иметь хотя бы указанное количество:
при 5 слитках `/blocknet get Kirill iron_ingot 3` сразу сообщает `already have 5 iron_ingot` и
ничего не переносит. При 2 слитках и 3 в знакомом сундуке `get iron_ingot 5` забирает только 3;
если запаса контейнеров недостаточно, недостающее производится рецептами тем же планировщиком.

При нехватке материалов задачи ничего не выполняют и отвечают одной строкой `blocked: missing …`.
Если рядом запомнены контейнеры с неизвестным или устаревшим содержимым, отчёт добавляет
`unchecked containers nearby: chest 12 64 -30`. Мозг сам выбирает, вызвать ли `look-in`.
Если подходящего выполняемого рецепта цели нет, ответ — `blocked: no recipe for <item>` с
сохранёнными подсказками `can be made in <type>`. Рабочие команды `plan`, `craft`, `get` выбирают
только рецепты сетки, верстака и поддерживаемых печей. API планировщика без ограничения станций
по-прежнему позволяет исследовать камнерез, кузнечный стол и костёр.
Сетку 2×2 и верстак заполняет ванильный `ServerPlaceRecipe`, результат
забирается кликом меню; между действиями по 3 тика. К запомненной станции вне досягаемости бот подходит
через существующую навигацию. Если станции нет, бот ставит её из инвентаря и открывает правым кликом,
добавляя в отчёт `placed own crafting_table` (или вид другой станции). `craft` и `get` сами не
осматриваются и не открывают неизвестные сундуки: перед ними мозг может явно вызвать `look`.
Печь, плавильная печь и коптильня работают через `AbstractFurnaceMenu` и обычные тики мира (200/100/100
тиков на предмет для стандартных рецептов). Бот закрывает меню на время готовки, забирает выход и
остатки топлива. Свой верстак после работы ломает и подбирает; свою печь подбирает только подходящим
инструментом, иначе сообщает `left a furnace at x y z`.
Перед каждым шагом входы проверяются заново: при изменении инвентаря допустим один новый расчёт.
Успех — один итоговый отчёт по фактически выполненным действиям, включая переносы ингредиентов:

```text
crafted 1 iron_pickaxe (took 3 iron_ingot from chest 12 64 -30)
got 5 iron_ingot: had 2, took 3 from chest 12 64 -30
got 4 stick: crafted 4
got 5 iron_ingot: had 2, took 2 from chest 12 64 -30, crafted 1
```

Размер партии рецепта может превысить заказ: `craft stick 1` из двух досок сообщает
`crafted 4 stick`; исходные палки сохраняются. После перепланирования учитываются уже выполненные
рецепты и переносы, чтобы повторно не заказать готовую часть и не потерять её в отчёте.
Ошибка выполнения — один `failed: step <k> <recipe>: <reason>`.
`stop`, другая задача, смерть и выход закрывают меню и завершают крафт одним отчётом; ваниль возвращает
неиспользованные ингредиенты сетки в инвентарь.

`CraftObtainGameTests` проверяет новые предметы сверх исходного запаса, сохранность цели в сундуке,
отсутствующий/невыполняемый рецепт, получение из инвентаря, ближайшего контейнера и рецептов,
смешанные источники, фактический размер партии и оба режима команд планирования.
Из прежних проверок изменены `keepsDataComponentsDistinct` (теперь явно `OBTAIN`, поскольку
проверяет получение имеющихся палок), `unsupportedProcessHasTypeHint` (`NO_RECIPE` вместо
`MISSING`, подсказка сохранена) и прежняя `unsupportedStationFailsWithoutInventingOutput`
(теперь `unsupportedStationCannotReplaceMissingRunnableRecipeInputs`: неподдерживаемый камнерез
не заменяет недостающие материалы выполняемого рецепта; ответ `blocked: missing`).
В `craftsWithWithdrawStepFromRememberedChest` дополнительно проверяется точный отчёт с координатами
сундука; прежние проверки переноса и крафта сохранены.

`follow` требует сущность у источника команды: из серверной консоли он не работает.
Бот обновляет маршрут после перемещения цели более чем на 3 блока, рядом останавливается и
смотрит на неё. Земля и запасные стратегии маршрута выбираются как у `come`; неудача отдельного
маршрута не прерывает сопровождение. За стеной бот идёт к последней увиденной позиции.
Выход цели, смерть или смена измерения завершают сопровождение одним сообщением об ошибке;
`stop` и замена задачи — сообщением `stopped`.

При наличии блоков первый поиск сравнивает ходьбу, копание и постройку по времени; запасные стратегии — падение и ближайшая доступная точка. Установка стоит 8 тиков, ломание — время игрока с лучшим инструментом и штраф 25 тиков против разрушений; защита построек игроков пока отдельная будущая задача.

## Круговое меню

Клиентская часть мода необязательна: без неё бот работает, а меню только отправляет те же команды.
Зажми колёсико мыши, веди мышь к пункту и отпусти (или кликни левой кнопкой); отпустил в центре —
отмена. Пока колёсико занято меню, ванильный «выбор блока» на нём выключен. Клавиша меняется в
настройках управления (категория Blocknet). Пункты: «Ко мне», «Следуй за мной», «Стоп» и
«Копай это» (блок под прицелом в момент открытия меню). «Следуй за мной» отправляет
`blocknet follow`, «Стоп» — `blocknet stop`.

## Тестовые моды

Тело не знает ни одного мода по имени (ADR-0005). Чтобы проверять его на настоящем модовом контенте,
в локальные запуски и GameTest подключены тестовые моды: сейчас Applied Energistics 2 и его
зависимость GuideME. Версии лежат в `gradle.properties`. Это только `localRuntime`, в зависимости
собранного мода они не попадают. Тесты в `ModdedBlockGameTests` проверяют поведение (добыть модовый
блок, открыть меню модовой машины), а тестовый мод можно заменить любым другим.

## Git

- `main` всегда собирается. Прямо в `main` не коммитим, кроме первого коммита.
- Ветки: `feat/…`, `fix/…`, `docs/…`, `refactor/…`, `build/…`.
- `spike/…` — эксперименты для проверки гипотез. Такие ветки целиком в `main` не вливаем: выводы
  записываем в ADR, а нужный код переносим отдельным чистым коммитом.
- Сообщения коммитов в стиле [Conventional Commits](https://www.conventionalcommits.org/):
  `feat(body): spawn bot as ServerPlayer`, `docs(adr): …`, `build: …`.
- Pull request вливается через squash (одна задача — один коммит в `main`) или через rebase, если
  в нём несколько аккуратных коммитов, которые стоит сохранить.
- Релизы помечаются тегами `vX.Y.Z` по semver.
- Секреты (API-ключи, токены моста) в репозиторий не попадают: только `.env` и локальные
  конфиги, они в `.gitignore`.

## Как менять архитектуру

Решения записаны в `docs/adr/`. Если решение меняется, старый ADR не переписываем: создаём новый
со статусом «Принято», а у старого ставим «Заменено ADR-NNNN». Так в истории видно, что и почему
поменялось.

## Лицензии и сторонний код

- Наш код — MIT (`LICENSE` в корне). Новые файлы вне `pathing/` и `crafting/` пишем под MIT.
- `pathing/` — вынесенный Automatone под LGPL-3.0-or-later. Заголовки лицензии в файлах
  сохраняем, изменения в этом модуле публикуем под LGPL-3.0. В модуль кладём тексты LGPL-3.0 и
  GPL-3.0 (LGPL дополняет GPL). Код из `pathing/` не копируем в MIT-часть.
- `crafting/` — адаптация расчёта AE2 под LGPL-3.0-or-later; те же правила изоляции,
  атрибуции и отдельного вложенного jar. См. `crafting/README.md`.
- В собранном моде `pathing/` и `crafting/` — отдельные вложенные jar; `mod_license` содержит обе лицензии
  (`MIT AND LGPL-3.0-or-later`: заголовки Baritone разрешают «версию 3 или любую более позднюю»),
  а отдельный вложенный jar позволяет заменить библиотеку,
  и требование LGPL соблюсти проще всего.
- Чужие исходники, скачанные только для чтения, кладём в `_src/` — он в `.gitignore`.

## Мозг через Claude Haiku

Нужны Node 22 и уже установленный/авторизованный `claude`. npm-зависимостей нет.
После запуска сервера и `/blocknet spawn Kirill` из корня репозитория:

```bash
node brain/brain.js --bot Kirill
# Для своего Prism-инстанса или выделенного сервера:
node brain/brain.js --bot Kirill --alias Кирилл,Кир --config "<minecraft>/config/blocknet_ai-bridge.json"
node --test brain/test/
```

По умолчанию config — `mod/run/config/blocknet_ai-bridge.json`; мод создаёт порт 25599 и случайный
токен при первом старте сервера. Env-альтернативы: `BLOCKNET_CONFIG`, `BLOCKNET_BOT`,
`BLOCKNET_ALIASES`. Мост слушает только `127.0.0.1`, переподключение автоматическое.
Чат `Kirill, ко мне` / `Kirill stop` / `Kirill follow` / `Kirill, осмотрись` обрабатывают правила;
прочие обращения вызывают Haiku. Stop прерывает и текущий вызов Claude. После смерти бота нужно
создать снова. `/blocknet` и круговое меню работают и без мозга.

`brain/mcp.json` генерируется при запуске (для других имён — `mcp.<имя>.json`); встроенные tools
Claude отключены, включён strict MCP, auth подписки сохраняется без `--bare`. В Windows нужен
нативный `claude.exe` (`CLAUDE_BIN`), либо `CLAUDE_BIN=node` и `CLAUDE_SCRIPT=<путь к cli.js>`
для npm-установки: shell не запускается. Все флаги собраны в `buildClaudeArgs`.

`brain/state.json` хранит session_id на бота, новая сессия после 30 ходов.
`brain/logs/usage.jsonl` — время/длительность вызова, токены input/output/cache, стоимость из JSON
Claude и число вызовов MCP. Логи, state и сгенерированные MCP-конфиги игнорируются git.
Подробности и точная команда: [brain/README.md](../brain/README.md).

ADR-0009 step 3: `look [focus]` returns a ranked scene with refs; `scene` reads memory instantly, `explore [direction|auto] [distance=32]` observes safe steps then looks. `goto <ref>` and `gather <ref|category|item> [count]` use observed targets and report the next useful tool.
The brain receives scene deltas; MCP allows eight tool calls per model turn. Self support is body sense, never visual map evidence.


### Mining and recent looks (2026-10-28)

`/blocknet descend [name] <y> [N|E|S|W]` digs a straight, one-wide, two-high 45-degree staircase; omitted direction uses the bot's facing. `/blocknet tunnel [name] <N|E|S|W> <length>` digs a 1x2 tunnel at the current level (1..64 blocks). Both use survival tool selection, real block breaking and pickup, require a pickaxe for stone, and stop before observed water/lava, unsupported drops or void. Reports include newly uncovered ores and object refs. Torch placement is not automatic.

Every own break queues occlusion-honest eye rays to the six exposed neighbours. `gather coal 3` follows newly seen connected ore; bridge gather accepts refs in `item`, `ref` or `target`. Nearby `look` reuses a panorama for 30 seconds within 6 blocks, takes one frontal 128x72 frame and appends `(looked Ns ago)`; `look fresh` (bridge `fresh: true`) forces the full turn. Background coverage is about 160 x 90 degrees at the existing 64x36 ray budget.

The brain keeps the latest player's message in prompts, answers in that language even during autoplay, and limits autonomous speech to meaningful events at most once every three turns. Prefer descend/tunnel for underground ores and gather for veins.

When Prism is running, preserve its deployed jar: `./gradlew build --offline -x deployJar -x removeOldDeployedJars`. Set `JAVA_HOME` to JDK 21 and, if the shell selects another user home, `GRADLE_USER_HOME=C:/Users/ncpaa/.gradle`. A busy default bridge port is logged and does not stop GameTests; bridge tests bind a separate free port.


## Live autoplay recovery (second session)

- Build with a client running: `./gradlew build --offline -x deployJar -x removeOldDeployedJars`.
  Set JDK 21 first; set `GRADLE_USER_HOME=C:/Users/ncpaa/.gradle` if the wrapper misses its cached distribution.
- Death uses vanilla drops/keepInventory, then `PlayerList.respawn` after 20 ticks. The respawn mixin
  preserves BotPlayer and its inert listener; BotManager tracks the replacement. `bot.died` carries
  cause, position and items_dropped, followed by `bot.respawned`. Brain waits and continues autoplay.
- Danger pauses task ticking/timeouts and navigation (including idle pathing hotbar rearrangement). It fights visible approaching hostiles within
  6 blocks or mob attackers, through player attacks and cooldown: sword > axe > pickaxe > hand.
  It retreats below 30% health or when outnumbered, and resumes after threats die or leave 16 blocks.
  Moments: danger_reflex, danger_clear; only danger_failed wakes the brain (low health or continuing
  damage after 10 seconds). `attack <ref|type>` uses observed entity UUIDs or type names.
- Craft station recovery is best effort: pickup failure reports `crafted ... (left a crafting_table at x y z)`.
  Material forecasts reject deficits, allowing incidental pickups. Opening a remembered station allows
  navigation time rather than only the short menu timeout.
- `look food` includes observed animals, food drops and crops. Failed look-in hints remembered containers.
  Bridge goto/gather accept quoted comma-separated refs (first ref) and nested `{ref: "t1"}` values.
- Sessions rotate every 10 turns with goal, inventory summary, last 3 task results and current scene line.
  Scene output caps at 12 lines and two representatives per class, including structures.
- After BridgeTools changes run `node brain/bench/sync-tools.js`; tools.json is generated.
  Socket GameTests use port zero; an occupied live bridge port warns without failing the server.
  Run the full GameTest suite once at the end; inspect required tests, failed at and exceptions in
  build/gametest.log. Node: `node --test brain/test/`. Leave changes uncommitted when requested.


### Навыки получения ресурсов (ADR-0010, шаг 1)

`knowledge iron_ore` показывает справочные диапазоны, не местонахождение блоков; индекс
обновляется при datapack reload. `mine_ore raw_iron 3` выбирает известный пик ниже бота и копает
лестницу и ветки, собирая замеченные жилы. При жидкости пробует безопасный обход;
при остатке инструмента ≤5 применений останавливается с причиной. Нужна подходящая кирка.

`obtain wooden_pickaxe 1` начинает с видимого дерева; `obtain iron_ingot 3` получает инструменты,
сырьё, топливо и станции через тот же планировщик и игровые меню. Сначала считается инвентарь,
затем используются запомненные контейнеры. Ограничения: 10 минут, 16 уровней рекурсии и 256
операций. Неизвестные способы (например, дроп мобов) дают `unsupported: ...`. В мост идут
`task.progress` каждые ~10 игровых секунд и один `task.finished`.

Шесть `KnowledgeSkillGameTests` проверяют справку о железе, вскрытую туннелем руду, одинаковые
первые блоки двух туннелей со скрытой рудой и без неё, деревянную кирку с нуля от видимой
берёзы, каменную кирку с добычей булыжника и быстрый отказ для дропа мобов. Тест дерева ждёт
освещения. Полный набор запускать один раз в конце и проверять `required tests`, `failed at`,
исключения в `build/gametest.log`. Занятый порт живого моста не мешает GameTests; socket-тесты
используют порт 0.
