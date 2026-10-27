# Blocknet brain

Node 22, plain ES modules, no npm dependencies. The mod is the body; this process uses rules for
short orders and Claude Haiku through your authenticated `claude -p` installation for other requests.

1. Start Minecraft with the mod and spawn a bot: `/blocknet spawn Kirill`.
2. From the repository root, run:

   ```sh
   node brain/brain.js --bot Kirill
   ```

3. In player chat: `Kirill, ко мне`, `Kirill stop`, `Kirill, осмотрись`, or a longer request such as
   `Kirill, сделай деревянную кирку`. Add `--alias Кирилл,Кир` for Russian aliases.

The default config path is `mod/run/config/blocknet_ai-bridge.json`, created by the mod on the first
server start. For Prism or another server directory, use:

```sh
node brain/brain.js --bot Kirill --config "/path/to/minecraft/config/blocknet_ai-bridge.json"
```

`BLOCKNET_CONFIG`, `BLOCKNET_BOT`, and `BLOCKNET_ALIASES` supply the same defaults as `--config`,
`--bot`, and `--alias`. Config contains `port` (25599 by default) and a generated `token`; restart
the Minecraft server after editing it. TCP binds only to `127.0.0.1`. The client reconnects every
second; interrupted actions are never replayed automatically. Spawn the bot again after death.

The brain generates `brain/mcp.json` at startup (`brain/mcp.<name>.json` for other bots), pointing
Claude to `mcp-server.js` with the absolute config path and bot name. The token stays in the mod's
config. The MCP wrapper exposes the body's registry and waits up to ten minutes for a task outcome.
It uses JSON lines on stdio, supports MCP `2026-07-28` metadata/discovery and initialization-based
clients including `2025-11-25` (see the [MCP spec](https://modelcontextprotocol.io/specification/latest/basic/versioning)).

The Claude command for Kirill is built in one function, `buildClaudeArgs` in `brain.js`:

```sh
claude -p --model haiku --mcp-config brain/mcp.json --strict-mcp-config --tools "" --allowedTools mcp__blocknet --permission-mode dontAsk --output-format json --system-prompt-file brain/prompt.md
```

The short user prompt is passed through stdin. Later calls append `--resume <session_id>`.
`brain/state.json` keeps one session per bot; after 30 successful turns the next call starts a new
session. Built-in tools are disabled. Subscription authentication is retained (no `--bare`).

`claude` must already be installed and authenticated. `CLAUDE_BIN` overrides its executable.
On Windows use native `claude.exe`; for an npm installation whose `claude` entry is a `.cmd` shim,
set `CLAUDE_BIN` to `node` and `CLAUDE_SCRIPT` to the absolute installed Claude `cli.js` path.
The brain spawns the executable directly with `shell: false`.

Short stop/come/follow/look orders in English and Russian bypass Claude. Messages queue during a
Claude call. `стоп`/`stop` acts immediately, kills the active Claude call and resets its session.
Queued requests are cleared on stop. The stable English system prompt is in `prompt.md`; replies are limited to two short lines / 300
characters and sent through `say`. The body keeps its normal mechanics and reports failures/hints.

`brain/logs/usage.jsonl` records each Claude invocation's time, duration, input/output/cache tokens,
cost and per-model fields from Claude JSON, and tool call count. `tool-calls.<bot>.jsonl` is the
per-call MCP counter (reset before each invocation). Failed calls have an error and null usage if
Claude returned no JSON. State, generated MCP configs and logs are ignored by git.

```sh
node --test brain/test/
```

Tests cover rule routing, stdio MCP framing/listing/task outcomes against a fake body, and Claude
arguments. Real Claude authentication and execution require a connected environment.
