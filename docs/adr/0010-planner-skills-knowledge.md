# ADR-0010: Планировщик, навыки, знания и критик

- Статус: Принято (2026-10-28)
- Дополняет: [ADR-0006](0006-cheap-brain.md) (дешёвый мозг, простые тулсы) и
  [ADR-0003](0003-brain-outside-mod.md).
- Пересмотреть, если: одна быстрая модель начнёт стабильно решать многошаговые цели сама, без плана
  и навыков (проверять стендом `brain/bench`).

## Контекст

Во второй автоигре (28.10) бот уже видел мир, но действовал бестолково. Он выкопал 1 уголь и
бросил жилу, не нашёл железо, ночью его убил зомби. Причина не в том, что модель «не знает, что
руда под землёй». Причина в устройстве мозга:

- одна быстрая модель (`gpt-6-luna`, минимальный reasoning) и планирует долгую цель, и реагирует
  на каждую мелочь;
- плана нет. Каждый ход — «продолжай» плюс изменение сцены, и цель тонет в истории в ~145 тыс.
  токенов;
- нет тулсов, чтобы уйти под землю, а подсказки вели к `explore` по поверхности;
- нет знаний о модпаке: на каких высотах генерируются руды в этой сборке;
- никто не проверяет прогресс.

Так же устроены и исследовательские агенты для Minecraft (Voyager, JARVIS-1, DEPS, Plan4MC):
планировщик → навыки → критик → память навыков.

## Решение

### Роли

1. **Планировщик** — сильная модель, вызывается редко: на новую цель и после провала подцели.
   Выдаёт план:
   ```json
   {"goal": "железная кирка",
    "steps": [
      {"id": 1, "do": "obtain stone_pickaxe 1", "done": "inv stone_pickaxe >= 1"},
      {"id": 2, "do": "obtain iron_ingot 3",   "done": "inv iron_ingot >= 3"},
      {"id": 3, "do": "craft iron_pickaxe 1",  "done": "inv iron_pickaxe >= 1"}],
    "budget_min": 20}
   ```
   `do` — вызов навыка или тулса либо короткая задача для исполнителя на естественном языке. `done`
   — условие, которое проверяется кодом: инвентарь, позиция, поставленный блок.
2. **Исполнитель.** Подцель с вызовом навыка выполняет тело, без LLM. Подцель на естественном языке
   решает быстрая модель с маленьким контекстом: цель, подцель, состояние, сцена.
3. **Критик** — код, а не модель. Проверяет `done` после каждой подцели и следит за прогрессом. Нет
   прогресса за бюджет подцели или навык провалился — планировщик получает причину и перепланирует,
   не больше 3 раз. Потом бот коротко говорит игроку, что не вышло и почему.
4. **Реакции** (чат, мелкие просьбы, события) — правила или быстрая модель, как сейчас. Опасность —
   рефлекс тела.

План хранится в мозге, вне контекста модели, и каждый ход подаётся одной строкой
(`план: шаг 2/3 obtain iron_ingot 3 — 1/3, копаю на y=16`). Сессия исполнителя короткая и
перезапускается на каждую подцель.

### Знания (тело, без имён модов)

На старте сервера тело выгружает знания из реестров. Это знание «как из вики»: оно не говорит, где
лежит конкретный блок, поэтому ADR-0002 не нарушается.

- **Руды.** По биомам измерения: `BiomeGenerationSettings` → placed features →
  `OreConfiguration` (какие блоки-цели) и `HeightRangePlacement` (`UniformHeight` /
  `TrapezoidHeight`: диапазон и пик), число попыток на чанк. Получается «iron_ore / deepslate_iron_ore:
  y −24…56, пик 16; также 80…384 в горах».
- **Добыча.** Нужный инструмент и уровень (пробы `isCorrectToolForDrops`), что выпадает (таблицы
  добычи).
- **Крафт** — планировщик крафта (ADR-0007).

Тулс `knowledge <item|block>` даёт короткую справку. Те же данные использует навык `obtain`.

### Навыки (тело, детерминированно, честно)

- `mine_ore <ore> <count>`: выбрать высоту по знаниям → `descend` → ветки (главный туннель 1×2,
  боковые через 3 блока) → дочищать жилы, которые видны → остановиться при нужном количестве или по
  бюджету. Только глаза и вскрытые клетки. Лава, вода и пустоты — стоп и обход.
- `obtain <item> <count>` — рекурсивно, как игрок:
  1. уже есть в инвентаре — готово;
  2. помнит в сундуках — `withdraw`;
  3. крафтится или плавится — планировщик крафта, ингредиенты через `obtain`;
  4. ломается из блоков: дерево (деревья в памяти или `explore`), камень и земля (копать вниз), руда
     (`mine_ore`);
  5. иначе (дроп мобов, торговля) — `unsupported: <как добывается>`.

  По дороге шлёт прогресс (`task.progress`): «копаю ветку на y=16, железа 2/3». Отчёт один, итоговый.

Каждый навык — одно понятное действие (ADR-0006), просто большое, как `craft`. Мелкие тулсы
остаются для случаев, которые навыки не покрывают.

### Память навыков

Удачный план (цель → шаги) сохраняется. Похожая цель повторяется без планировщика, а планировщику
показывается как пример. Уроки рефлексии (`lessons.md`) остаются.

### Модели

Роли выбираются стендом `brain/bench` отдельно:

- планировщик — качество плана (сценарии «железная кирка с нуля», «дом до ночи»);
- исполнитель — скорость и точность одного шага.

Кандидаты: планировщик — Sonnet, `gpt-6.1-terra`, `gpt-6.1-sol` (medium); исполнитель — `gpt-6-luna`,
Haiku.

## Последствия

- Цель «сделай железную кирку» стоит 1 вызов планировщика плюс 2–3 навыка, а не десятки метаний
  быстрой модели.
- Тело растёт: знания, `mine_ore`, `obtain`. Их надо покрыть GameTest-ами, включая честность: навык
  не выбирает направление по скрытой руде.
- Порядок:
  1. **Готово (2026-10-28):** знания и `knowledge`; `mine_ore` и рекурсивный `obtain` поверх существующих действий;
  2. **Готово (2026-10-28):** планировщик, критик, план в мозге, роли моделей и стенд по ролям;
  3. **Готово (2026-10-28):** память успешных планов.

## Реализация шагов 2–3

`brain/plans.js` хранит план и исполняет шаги, `brain/planner-prompt.md` задаёт JSON-контракт.
По умолчанию `--planner claude:sonnet`, `--actor codex:gpt-6-luna:priority`. Формат настройки —
`backend:model[:tier|effort]`; сессии ролей независимы, сессия исполнителя сбрасывается на каждом
шаге. Планировщику через отдельный MCP доступен только `knowledge`; остальные тулсы перечислены
в его контексте для составления плана, но вызывать их он не может.

Простая эвристика выделяет цели из чата; исполнитель может передать запрос через `needs_plan`.
Автоигра планирует цель и заканчивает её выполнение после успеха или окончательного провала.
Один вызов навыка/тулса в `do` исполняется напрямую без LLM, свободный текст получает только
цель, текущий шаг, состояние, сцену, язык игрока и строку статуса плана. Неверный JSON, аргументы
или непроверяемый `done` исправляются одним повторным вызовом планировщика.

Критик читает `inv` и `status`, считает предметы без повторного учёта руки и summary. Реализованы
`inv <item> >= <count>` и `pos <x> <y> <z> [within <radius>]`. Условие поставленного блока пока
не реализовано: позиция доказывает приход, но не существование дома. Такие предикаты отвергаются
валидатором. План содержит 1–32 последовательных шага и `budget_min` в (0,120]; бюджет шага —
равная доля или собственный `budget_min`, общий исходный дедлайн действует и после перепланирования.
Провал тулса, непроверенный результат или превышение бюджета передаются планировщику с причиной.
Лимит конкретизирован: три провала шагов суммарно, то есть максимум два перепланирования после
первой попытки; затем короткий ответ игроку. Таймаут останавливает тело и исполнителя.

В каждом сообщении исполнителю есть строка `план: шаг 2/3 obtain iron_ingot 3 — 1/3`.
Чат `план?` получает её сразу, в том числе во время работы навыка. Stop/смерть отменяют план;
continue/respawn могут снова запустить цель автоигры. Обычный чат стоит в очереди до конца плана.

Успех сохраняется в `brain/logs/plans.jsonl`: `goal`, нормализованный `goal_key`, `steps`,
`budget_min`, `duration_ms`. Точное совпадение или сходство множеств слов >=0.8 повторяет план
без планировщика, числа должны совпадать. После провала кеш обходится. Два ближайших примера
передаются планировщику. Невалидные записи библиотеки пропускаются.

`brain/bench/run.js --role planner|actor` выбирает отдельные сценарии: JSON/навыки/порядок/
условия для «железная кирка с нуля» и «дом до ночи» либо точность одиночного obtain/craft.
Без `--role` сохраняется прежний набор реактивных сценариев. `--fake` не вызывает модели.
Проверка: `node --test brain/test/ brain/bench/test/`; тесты доказывают планирование из чата,
проверку инвентаря, перепланирование с причиной и лимит провалов, повтор из JSONL и обход
исполнителя прямыми шагами. Проверка настоящих моделей и Minecraft в эту работу не входит.

## Реализация шага 1

`knowledge/Knowledge` строит индекс на старте сервера и после общего datapack reload: биомы
измерения → placed features → OreConfiguration, высоты и частота размещений. Добыча — из loot
tables и проб ToolFacts. Неизвестные height providers дают `unknown range`. Варианты руды
объединены по предмету дропа. Это справочные данные, без координат мира.

`mine_ore` выбирает наиболее частый известный пик ниже бота, спускается и копает туннель 1×2:
ветки через три блока, по 12 блоков в обе стороны. Увиденная руда прерывает расписание для сбора
жилы; скрытые состояния не выбирают направление. Жидкость останавливает проход; основной
туннель пробует параллельный обход, при небезопасном обходе останавливается. Остаток инструмента
≤5 применений останавливает добычу. Бюджет — 12000 игровых тиков, прогресс — каждые 200 тиков.

`obtain` держит стек целей (глубина ≤16, ≤256 операций), сначала проверяет инвентарь и память
контейнеров, затем использует планировщик и добычу сырья. Прогноз с увиденными породами дерева
делается на копии инвентаря; предметы появляются только через обычные игровые действия.
Результаты внутренних действий объединяются в один итоговый отчёт. Дроп мобов определяется
по таблицам добычи и возвращает `unsupported: mob drops: ...` без охоты.

Замер полного прогона: индекс — 10 групп руд, 97 мс; 164/164 обязательных GameTests за 24,87 с
(включая 6 новых). Деревянная кирка с нуля от берёзы — 676 тиков, каменная — 409 тиков,
2 raw_iron с туннелем — 632 тика. Первые 6 блоков горизонтальных туннелей со скрытой рудой
и без неё совпали. Build с исключённым деплоем и 19/19 node-тестов прошли; полный прогон
выполнен один раз, лог — `build/gametest.log`.

## Уроки третьей живой автоигры (2026-10-28)

`build/autoplay-report-3.md` и `build/autoplay-3-session.jsonl` выявили разрыв между плоскими
тестами и обычным миром: камень под травой был выбран без доступа; remembered cliff ref
отбрасывался фильтром текущей видимости; первый водяной карман завершал спуск на y=50,
задолго до железного пика ~16. Неполный отчёт ошибочно считался успешным в MCP.

- Для булыжника увиденная земля/песок/сыпучий грунт под ногами разрешает начать лестницу.
  Сначала открывается голова, затем ноги; каждый следующий блок виден перед выбором.
  Разрушение покрытия не считается неудачной попыткой собрать булыжник. При неизвестном
  ингредиенте тега каменных инструментов предпочитается поддерживаемый источник cobblestone.
- Ref — запомненное место, куда надо подойти и снова посмотреть. Cliff даёт булыжник, а не air.
  Последние 512 выданных refs переживают перегруппировку объектов в пределах сессии; старый
  снимок разрешает подход, но не разрушение без свежего луча.
- `mine_ore` подтверждает достижение выбранной высоты до перехода к горизонтальным веткам.
  Наблюдаемая жидкость или непроверенный пол запускает ограниченный детерминированный обход:
  лестница меняет направление на три шага, затем возвращается к исходному. Видимый источник
  воды можно закрыть подходящим полным, несыпучим блоком через меню инвентаря и `useItemOn`.
  Лава не закладывается. Когда безопасных вариантов нет, отчёт объясняет остановку.
- Подбор дропа ограничен уже проверенной ступенью: предмет может отскочить над следующей
  неизвестной пещерой. Выбор следующей ступени ждёт приземления, иначе луч из прыжка попадает
  в потолок и создаёт ложное «проход загорожен».
- Основной проход проверяется после каждого шага; каждые три шага — по 12 шагов в обе стороны.
  Возврат идёт к сохранённому перекрёстку, а не на предполагаемое обратное расстояние после
  сбора жилы/обхода. Скрытая руда не участвует в выборе направления.
- В состоянии и строке мозга есть текущий y, исходная поверхность, пик и расстояние до него.
  Прогресс/итог содержит фактический счёт предметов, шаги прохода/веток, обходы и закладки.
  `stopped`, `unsupported`, `none seen` и частичный проход с `; stopped` — ошибки тулса.
- Взмахи и трещины используют существующие `swing` и `ServerPlayerGameMode.tick` /
  `destroyBlockProgress`, без ускорения ломания. Новый тест перехватывает у соседнего игрока
  повторные `ClientboundAnimatePacket` и стадии `ClientboundBlockDestructionPacket`, включая
  сброс. Визуальный код не дублирует ванильный прогресс.

Доказательство — восемь `NaturalMiningGameTests` на колонне 32×48×32 и паре таких колонн,
со слоями земли, водой, пещерой, сланцем, углём и железом на масштабированном пике. Первые
45 реальных разрушений одинаковы с железом и без него; отдельный пустой забой проверяет
обе полные ветки и продолжение основного прохода. Плоские арены остаются для прежних контрактов.

Финальная проверка этой серии: build без deployJar/removeOldDeployedJars успешен; Node 42/42;
три полных прогона по 176/176 (41.53, 29.82, 31.44 с), без `failed at`. Реалистичная добыча:
3/3 raw_iron, основной проход 3 шага, ветка 1 шаг, два обхода; 2778–2790 игровых тиков.
Закладка источника и обе полные ветки отдельно подтверждены теми же прогонами.

## Live-run 3 follow-up: survival strategy and model speech (2026-10-28)

The planner now has a short stable survival playbook: wood, table, wooden pickaxe, stone tools,
coal and food, torches, cave ores, furnace and smelting, then iron tools. Fuel is a separate
requirement. Ore finding starts with look/scene and visible ore, then seen cliffs/cave mouths,
then bounded cave exploration. Branch mining at the knowledge peak is the fallback. Unfamiliar
items use knowledge/wiki rather than guessed recipes or acquisition methods.

A plan step can include both a budget and a fallback:

```json
{"do":"explore_caves iron_ore 3","budget_min":5,"else":"mine_ore iron_ore 3","done":"inv raw_iron >= 3"}
```

The critic runs `else` once after failure, unverified completion or timeout, without calling the
planner. It stops any old action, refreshes state and verifies the same `done` condition. The
fallback gets a fresh step budget, bounded by the original plan deadline. Only its failure calls
the planner again; existing failure limits still apply. A cached plan retains these fields.

`explore_caves <target> <count>` is a body skill over ordinary player actions. It selects an
observed cave-mouth frontier or dark opening, tries one fresh look when absent and otherwise
reports `no cave seen`. Walking uses only observed free cells and sturdy floors, with at most
one block of elevation change and observed natural ceilings inside the cave. Unknown floors,
fluids, observed lava and nearby hostiles are rejected. Periodic fresh looks and local frontiers
extend the route. Reachable wall ore uses the existing gather/vein-following machinery in a
stationary mode; it never walks across a drop to chase loot. Owned torches are placed through
normal player placement below light 7. The five-minute budget reserves return time (up to two
minutes); enough target drops also start return. The return follows checked breadcrumbs, with
one terminal report containing distance, actual item gains and torch count. A changed or blocked
return route reports failure. External stop/death cancels immediately. Progress is emitted every
200 ticks. Neither target selection nor route scoring reads hidden world states (ADR-0002/0009).

Optional plan `tool_gaps: [{"want":"missing action","why":"needed for this goal"}]` is validated
with the plan: at most 32 entries, nonempty strings of at most 200/500 characters. Each entry goes
to `brain/logs/tool-feedback.jsonl` with `source: planner` and to the session log, so `report.js`
shows it in Tool feedback alongside actor feedback.

`brain/persona.md` replaces local phrase pools and is the stable prefix of both role prompts.
It defines the player's casual Minecraft buddy and asks for one short intent line before tools
or planning JSON. Codex agent-message deltas/completed items and Claude stream-json text deltas
send this preamble to chat as soon as its first line is complete. The line is removed from the
remaining output; JSON and final actor text follow the existing paths. Stop/death suppress stale
speech. Trivial rules have no model preamble or canned ack. Player hints are acknowledged by the
tool-free classifier's model-written `reply`. Only the default language remains in persona.json.

For this iteration the user verifies new behavior live: no new tests or bench rules. Existing
phrase tests are adapted to streaming speech, and gathered-count checks accept incidental extra
drops while verifying actual report counts. Completion requires the offline build with deploy
excluded, one passing full existing GameTest run and existing Node tests.
