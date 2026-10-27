# Blocknet brain

Node 22, plain ES modules, no npm dependencies. The mod is the body; this process uses rules for
short orders and one persistent model process per bot for other requests. The default backend is
Codex (`gpt-6-luna`, priority service, low reasoning); Claude Haiku remains available. Both use
your existing subscription login, with no API key or npm dependencies.

1. Start Minecraft with the mod and spawn a bot: `/blocknet spawn Kirill`.
2. From the repository root, run:

   ```sh
   node brain/brain.js --bot Kirill --backend codex
   # Switch to Claude:
   node brain/brain.js --bot Kirill --backend claude
   ```

3. In player chat: `Kirill, ко мне`, `Kirill stop`, `Kirill, осмотрись`, or a longer request such as
   `Kirill, сделай деревянную кирку`. `Kirill, накопай 10 булыжника` routes straight to `gather`
   without a model call. Add `--alias Кирилл,Кир` for Russian aliases.

The default config path is `mod/run/config/blocknet_ai-bridge.json`, created by the mod on the first
server start. For Prism or another server directory, use:

```sh
node brain/brain.js --bot Kirill --config "/path/to/minecraft/config/blocknet_ai-bridge.json"
```

`BLOCKNET_CONFIG`, `BLOCKNET_BOT`, and `BLOCKNET_ALIASES` supply the same defaults as `--config`,
`--bot`, and `--alias`; `BLOCKNET_BACKEND` supplies the backend default. `--max-turns N` rotates
the model process after N successful turns (default 30). Config contains `port` (25599 by default) and a generated `token`; restart
the Minecraft server after editing it. TCP binds only to `127.0.0.1`. The client reconnects every
second; interrupted actions are never replayed automatically. Spawn the bot again after death.

The brain generates `brain/mcp.json` at startup (`brain/mcp.<name>.json` for other bots), pointing
Claude to `mcp-server.js` with the absolute config path and bot name. The token stays in the mod's
config. The MCP wrapper exposes the body's registry and waits up to ten minutes for a task outcome.
It uses JSON lines on stdio, supports MCP `2026-07-28` metadata/discovery and initialization-based
clients including `2025-11-25` (see the [MCP spec](https://modelcontextprotocol.io/specification/latest/basic/versioning)).

Each backend module has one spawning function and accepts an injectable `spawnProcess` for tests.
Both implement `send(text) -> {text, usage, toolCalls, ms, ttftMs}` and `close()`.
The Claude command for Kirill is built by `buildClaudeArgs` in `claude-backend.js`:

```sh
claude -p --model haiku --mcp-config brain/mcp.json --strict-mcp-config --tools "" --allowedTools mcp__blocknet --permission-mode dontAsk --input-format stream-json --output-format stream-json --verbose --include-partial-messages --system-prompt-file brain/prompt.md
```

Each player request is a `type: "user"` JSON line on the same stdin. The process stays alive;
each `result` event finishes a call and supplies usage. There is no per-message spawn or resume.
Built-in tools are disabled. Subscription authentication is retained (no `--bare`).

`codex-backend.js` starts `codex app-server --listen stdio://` with these `-c` overrides:

```text
model="gpt-6-luna"
service_tier="priority"
model_reasoning_effort="low"
approval_policy="never"
sandbox_mode="read-only"
project_doc_max_bytes=0
features.skip_host_skill_discovery=true
features.skill_search=false
features.plugins=false
features.apps=false
features.memories=false
features.shell_tool=false
features.multi_agent=false
web_search="disabled"
mcp_servers={blocknet={command="<node executable>",args=["<absolute brain/mcp-server.js>"],env={BLOCKNET_CONFIG="<absolute bridge config>",BLOCKNET_BOT="Kirill",BLOCKNET_AUDIT="<absolute brain/logs/tool-calls.Kirill.jsonl>"},default_tools_approval_mode="approve",tool_timeout_sec=600}}
```

These are separate argv entries, not shell commands. `thread/start` replaces the large coding
instructions with `prompt.md` via `baseInstructions`, and sets `approvalPolicy: "never"`,
`sandbox: "read-only"`, `serviceTier: "priority"`, and an ephemeral thread. Each `turn/start`
uses the same thread and `effort: "low"`. Host skill discovery, skill search, plugins, apps,
memories, project docs and the shell tool are disabled. Blocknet MCP consent elicitations are
accepted automatically; other server requests are rejected. JSON-RPC shapes follow the generated
bindings in `_src/codex-app-server/`. Codex usage is the change in cumulative thread tokens over
the whole turn, including its tool round trips.

`claude` must already be installed and authenticated. `CLAUDE_BIN` overrides its executable.
On Windows use native `claude.exe`; for an npm installation whose `claude` entry is a `.cmd` shim,
set `CLAUDE_BIN` to `node` and `CLAUDE_SCRIPT` to the absolute installed Claude `cli.js` path.
The brain spawns the executable directly with `shell: false`. Codex must also be installed and
authenticated; `CODEX_BIN` and optional `CODEX_SCRIPT` provide the equivalent overrides.

Short stop/come/follow/look orders in English and Russian and clean Russian gather orders bypass
the model. Messages queue during a call. `стоп`/`stop` acts immediately, closes the model process
and clears queued requests; death also resets the session. A dead process restarts on the next
request; an interrupted request is never replayed. Processes also rotate after `--max-turns` turns.
A brief “Щас.” / “Ок.” / “Делаю.” / “On it.” is sent if the answer takes more than 700 ms.
Each user message includes a compact inventory, held item and low-health line only when changed;
a fresh session gets the current line again. The English prompt asks for one casual sentence in
the player's language; delivery caps replies at 12 words / 200 characters.

`/blocknet gather [name] <item> <count>` and bridge `gather {item, count}` mine and collect that
many additional items. Candidates come from eye-observed WorldMemory and a quick glance. With
matching ground underfoot, the bot opens a simple forward/down staircase, checking each exposed
block before mining through the existing player code path. No ore prospecting or hidden-block
scan. With no resource or matching ground it reports `failed: no <item> in sight; look around
or go elsewhere`. Success reports once, e.g. `gathered 10 cobblestone`, plus tool wear when relevant.

`brain/logs/usage.jsonl` records each model call's backend, model, milliseconds, time to first
text token (when available), raw token usage, tool call count and error. `tool-calls.<bot>.jsonl`
is the per-call MCP counter (reset before each call). Failed calls have null usage when no result
was available. Generated MCP configs and logs are ignored by git.

```sh
node --test brain/test/
```

Tests cover rules, MCP framing/task outcomes, persistent Claude stream-json and Codex JSON-RPC
with fake children, usage, restarts, and slow/fast acknowledgements. No tests call a model.
Real authentication, end-to-end latency and model behavior require a connected live test.
