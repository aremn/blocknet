# Blocknet

An AI companion that plays modded Minecraft with you, as a real player.

> **Status:** early prototype. A bot joins as a real player, finds its way (around walls, up
> ledges, digging through when there is no way around), mines and right-clicks blocks with
> survival rules, driven by `/blocknet` commands or a radial menu. No perception or brain yet.
> Target: Minecraft **1.21.1**, **NeoForge 21.1** (All the Mods 10).

## Idea

- **Body** (`mod/`): a server-side NeoForge mod. The bot is a real `ServerPlayer`, so it uses the
  same server code paths as a human player: breaking blocks with real speed, right-clicking modded
  machines, moving items in any mod's menus. Clients don't need the mod.
- **Honest perception**: the bot only knows what it has seen with its own eyes (rays from its head),
  heard, or read through a machine it is looking at. No x-ray.
- **Brain** (planned, `brain/`): a separate process with an LLM (API or local). It talks to the body
  over a versioned JSON-RPC protocol, sets goals and reacts to events. The LLM never runs per tick.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the decision records in [docs/adr](docs/adr).

## Repository layout

| Path | What |
|---|---|
| `mod/` | NeoForge mod: the bot's body |
| `docs/` | Architecture, decision records (ADR), development guide |
| `licenses/` | Licenses of third-party material |
| `pathing/` | Pathfinding extracted from Automatone, LGPL-3.0-or-later, nested in the mod jar |
| `brain/` | *(planned)* LLM agent process |
| `protocol/` | *(planned)* JSON schemas of the body ↔ brain protocol |

## Building

Requires JDK 21.

```bash
./gradlew build
./gradlew :mod:runClient
```

More in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## License

Blocknet's own code is under the [MIT License](LICENSE). The `pathing/` module is derived from
Automatone and Baritone and stays under LGPL-3.0-or-later, with its own license files; the mod jar
carries it as a separate nested jar. Third-party
notices are in [licenses/](licenses).

---

**По-русски:** Blocknet — ИИ-напарник для модового Minecraft, который играет как настоящий игрок на
сервере. Тело — мод NeoForge, мозг — отдельный процесс с LLM. Архитектура и решения описаны в `docs/`.
Лицензия: MIT, кроме модуля `pathing/` (LGPL-3.0).
