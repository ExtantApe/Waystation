# Decisions

Choices the owner made for Waystation, newest first. Server-specific choices live with that server's own notes, not here.
Dates are when the decision was made. Those marked "by" were made while the first version was built, before that date.

| Date | Decision |
| --- | --- |
| 2026-11-07 | ReturnGate is folded into Waystation: a configurable return command sends the player to a fargate destination after a countdown that damage cancels. Multiverse-Core becomes optional, so a single-world server can run Waystation for fargates and the return command alone. |
| 2026-11-07 | Release as soon as it's good, as 0.x versions on GitHub Releases only. Modrinth, Hangar and Spigot wait for the owner's own code review. |
| 2026-11-07 | Paper only: `paper-api` and `paper-plugin.yml`, not Spigot. |
| 2026-11-07 | Target Minecraft 26.1 and later, built and run on Java 25. Drop 1.21.11. Test on Paper 26.1, 26.2 and 26.3. Use newer Paper features where they make things simpler or sturdier, not for their own sake. |
| 2026-11-07 | Released as 0.1.0, public under ExtantApe, MIT. |
| by 2026-11-07 | The default config lists no worlds, so a new server works without editing. An `example-worlds` flag makes two worlds to try it on. Real server settings stay in a private file. |
| by 2026-11-07 | Gradle with Kotlin DSL, group `com.extantape`, built like BlueMapHUD. Repo and jar named `Waystation`. |
| by 2026-11-07 | One jar, readable source. No economy plugin of its own, no database, no client mod. Money goes through Vault when it's there. |
| by 2026-11-07 | No dependency on Multiverse-Portals. Inventories must never be lost or mixed. Admin rights come from permissions (LuckPerms), not from being an operator. |
