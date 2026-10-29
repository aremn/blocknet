# Agents working in this repo

Read `.claude/skills/blocknet-dev/SKILL.md` first: it holds the environment (JDK 21 path), build and
GameTest commands, the code map, rules that came from real bugs, GameTest conventions and the git
workflow. Architecture is in `docs/ARCHITECTURE.md`, decisions in `docs/adr/`.

Short version:
- `export JAVA_HOME="/c/Users/ncpaa/.jdks/jdk-21.0.12.1+1"` before Gradle; use `--offline`.
- Prove behavior with GameTests: `./gradlew :mod:runGameTestServer --console=plain > build/gametest.log 2>&1`,
  then grep the log for `required tests` and `failed at` (Gradle's exit code is not enough).
- The body never names a mod (ADR-0005); the bot acts through player code paths; it must not see
  through walls (ADR-0002).
- `pathing/` and `crafting/` are LGPL-3.0-or-later (keep file headers); everything else is MIT.
- Commit each meaningful, tested step on a feature branch (Conventional Commits, see the skill), so work can be restored; push only when the user asks.
