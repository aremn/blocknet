# Blocknet brain

The bot is a worker (ADR-0016): the mod's body does each job completely by itself, and this Node 22
process only turns what a player says into jobs. One model call per message, no tools for the model.

```text
player: "принеси 32 железа"
model:  {"reply": "Ща.", "jobs": [{"job":"get","item":"minecraft:iron_ingot","count":32,"deliver":true,"done":"Держи."}]}
body:   obtain iron_ingot 32 -> give Alex iron_ingot 32
bot:    "Ща." ... "Держи."
```

Jobs (`jobs.md` is the model's whole instruction, after `persona.md`):

| Job | Body tasks | Says |
|---|---|---|
| `get {item, count, deliver, source}` | `obtain` (count = owned + N, or N when delivering), then `give` | done line |
| `smelt {item?}` | `smelt` | done line |
| `store {target p#, what}` | `deposit` | done line |
| `take {target p#, item, count?}` | `withdraw` | done line |
| `move {from p#, to p#}` | `transfer` | done line |
| `give` / `drop {item, count?}` | `give` / `drop` | done line |
| `come`, `follow`, `stop` | `come`, `follow` (not waited for), `stop` | nothing |
| `clear {radius}` | `clear` around the player | done line |

Anything else gets an honest "can't do that yet" and no job. Orders queue; "вместо этого" (`now`)
replaces the current work; `стоп` drops everything. A failed or partial report (`failed:`, `partly`,
`; stopped`) is explained in one plain line by a second model call; a success says the job's done line.
`стоп`, `сюда`/`ко мне`, `за мной` and `накопай 20 угля` need no model (`rules.js`). Questions are
answered from the State/Bag/Work lines the model gets with every message.

```sh
node brain/brain.js --bot Kirill
node brain/brain.js --bot Kirill --model claude:haiku --config "/path/to/minecraft/config/blocknet_ai-bridge.json"
node --test brain/test/ brain/bench/test/
```

`--model backend:model[:effort]` defaults to `codex:gpt-6.1-sol:low`. `--alias Кирилл,Кир` adds names;
`--listen name` makes the bot answer only when addressed. With one bot, bare chat reaches it.
The default config is `mod/run/config/blocknet_ai-bridge.json`, created by the mod on first start.

With `"brain": "<repo>/brain"` in the bridge config, `/blocknet spawn <name>` starts
`node <brain>/brain.js --bot <name> --config <that config>` itself and stops it on despawn or when the
world closes; output goes to `logs/blocknet/brain-<name>.log` in the game directory. Don't also run a
brain by hand for the same bot.

```json
{"port": 25599, "token": "...", "brain": "C:/Projects/blocknet/brain"}
```

## Logs

- `brain/logs/session-<date>-<bot>.jsonl`: chat, `translate` (the model's reply, jobs and rejected
  jobs), `job_start`/`job_end` with the body's report and time, tool calls and results, voice.
  Segments of 5 MB continue as `.jsonl.1`...; the last 20 sessions are kept.
- `brain/logs/usage.jsonl`: each model call's backend, model, ms, time to first token and tokens.
- `node brain/timeline.js [session] [--config ...]` merges a session with the body trace
  `logs/blocknet/<bot>-trace.log`; brain notes also show in `/blocknet debug`.
- `node brain/report.js [session]` prints a compact report of a session.

## Backends

`codex-backend.js` (Codex app-server over stdio JSON-RPC, subscription login) and `claude-backend.js`
(`claude -p` stream-json, persistent process, no `--bare`) both implement
`send(text) -> {text, usage, ms, ttftMs}` and `close()`, with injectable `spawnProcess` for tests.
The worker runs them in their tool-free `chat` role: no MCP servers, no web search, no shell.
`CODEX_BIN`/`CODEX_SCRIPT` and `CLAUDE_BIN`/`CLAUDE_SCRIPT` override the executables.

## Memory viewer

From the repo root run `./brain/start-viewer.ps1`, then open `http://127.0.0.1:25600` (top projection,
slices) or `/3d` (WebGL fly-over of remembered blocks). It reads only the body's `debug.memory`.
`node brain/viewer.js --snapshot build/memory-view-sample.json --port 25601` previews a saved sample.

## Voice (optional)

Players talk through Simple Voice Chat near the bot (24 blocks): the phrase is transcribed by a local
whisper.cpp server, shown back as `[bot heard] ...` and handled like chat; replies are also spoken with
Piper. `powershell -ExecutionPolicy Bypass -File brain/setup-voice.ps1` installs both into
`%LOCALAPPDATA%\blocknet` (`-Voices all` adds every Russian voice). The brain looks in the config's
`voice.home`, `BLOCKNET_VOICE_HOME`, `~/blocknet`, then `%LOCALAPPDATA%\blocknet`; paths can be set under
`"voice": {"stt": {...}, "tts": {...}}` in the bridge config. `/blocknet voice [name|next|list]` switches
the voice and remembers it in `piper/voices/chosen.txt`.

The planner, step actor, MCP server, skill scripts and model bench were removed in ADR-0016; the
last state with them is the git tag `before-worker`.
